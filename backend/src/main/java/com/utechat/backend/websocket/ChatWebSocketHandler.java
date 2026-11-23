package com.utechat.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.MessagingDtos.WsFrame;
import com.utechat.backend.service.CallService;
import com.utechat.backend.service.CallService.CallSignalingInfo;
import com.utechat.backend.websocket.WebSocketSessionRegistry.ManagedWsSession;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Bộ xử lý WebSocket thuần JSON (/ws, không STOMP):
 * quản lý đăng ký phiên, heartbeat ứng dụng JSON ping/pong 25s (kiểm tra thu hồi thiết bị + cập nhật last_seen_at),
 * chuyển tiếp typing.start / typing.stop (1 lần / 3 giây),
 * và khóa báo hiệu 2 thiết bị cho WebRTC (call.offer, call.answer, call.ice, call.keepalive — không ghi sync_events).
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Duration TYPING_THROTTLE = Duration.ofSeconds(3);
    private static final Duration DB_TOUCH_INTERVAL = Duration.ofSeconds(60);
    private static final Duration CALL_KEEPALIVE_THROTTLE = Duration.ofSeconds(10);

    private final WebSocketSessionRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final CallService callService;
    private final ConcurrentHashMap<String, Instant> lastTypingSent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastCallKeepaliveUpdated = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(
            WebSocketSessionRegistry registry,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock,
            CallService callService) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.callService = callService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        registry.touch(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ManagedWsSession managed = registry.find(session);
        if (managed == null) {
            return;
        }
        registry.touch(session);

        JsonNode root;
        try {
            root = json.readTree(message.getPayload());
        } catch (IOException e) {
            // Khung JSON lỗi cú pháp bị bỏ qua an toàn, không làm sập kết nối.
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }
        String type = root.path("type").asText("");
        switch (type) {
            case "ping" -> handleAppPing(managed);
            case "typing.start" -> handleTyping(managed, root.path("payload"), true);
            case "typing.stop" -> handleTyping(managed, root.path("payload"), false);
            case "call.offer", "call.answer", "call.ice" -> handleCallSignaling(managed, type, root.path("payload"));
            case "call.keepalive" -> handleCallKeepalive(managed, root.path("payload"));
            default -> {
                // Bỏ qua an toàn các loại khung không hỗ trợ.
            }
        }
    }

    /**
     * Xử lý heartbeat JSON ping mỗi 25s từ Android:
     * kiểm tra hạn JWT + trạng thái thu hồi thiết bị trong DB, đóng 4401 nếu hết hiệu lực,
     * cập nhật last_seen_at tối đa 1 lần / 60s và trả khung JSON pong.
     */
    private void handleAppPing(ManagedWsSession managed) {
        Instant now = clock.instant();
        if (!now.isBefore(managed.tokenExpiresAt) || !isDeviceStillValid(managed)) {
            registry.closeSafe(managed, WebSocketSessionRegistry.CLOSE_SESSION_EXPIRED);
            return;
        }

        if (Duration.between(managed.lastDbTouch, now).compareTo(DB_TOUCH_INTERVAL) >= 0) {
            managed.lastDbTouch = now;
            jdbc.update(
                    "UPDATE user_devices SET last_seen_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                    Timestamp.from(now), managed.deviceRowId, managed.userId);
        }

        try {
            WsFrame pong = new WsFrame(1, "pong", UUID.randomUUID().toString(), null, null);
            managed.session.sendMessage(new TextMessage(json.writeValueAsString(pong)));
        } catch (IOException | IllegalStateException e) {
            registry.closeSafe(managed, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    /**
     * Chuyển tiếp trạng thái đang gõ tới các thành viên hoạt động khác của hội thoại (không lưu vào sync_events).
     * Giới hạn typing.start tối đa 1 khung mỗi 3 giây theo cặp (userId, conversationId).
     */
    private void handleTyping(ManagedWsSession managed, JsonNode payload, boolean isStart) {
        if (payload == null || !payload.isObject()) {
            return;
        }
        String rawConvId = payload.path("conversationId").asText(null);
        if (rawConvId == null || rawConvId.isBlank()) {
            return;
        }
        UUID convId;
        try {
            convId = UUID.fromString(rawConvId.trim());
        } catch (IllegalArgumentException e) {
            return;
        }

        // Chỉ thành viên đang hoạt động (left_at IS NULL) mới được phép phát typing trong hội thoại.
        Boolean isActiveMember = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL)",
                Boolean.class,
                convId, managed.userId);
        if (!Boolean.TRUE.equals(isActiveMember)) {
            return;
        }

        if (isStart) {
            Instant now = clock.instant();
            String key = managed.userId + ":" + convId;
            Instant prev = lastTypingSent.get(key);
            if (prev != null && Duration.between(prev, now).compareTo(TYPING_THROTTLE) < 0) {
                return;
            }
            lastTypingSent.put(key, now);
        }

        Map<String, Object> outPayload = new LinkedHashMap<>();
        outPayload.put("conversationId", convId.toString());
        outPayload.put("userId", managed.userId.toString());
        outPayload.put("state", isStart ? "start" : "stop");

        WsFrame frame = new WsFrame(1, "typing", UUID.randomUUID().toString(), null, outPayload);
        String serialized;
        try {
            serialized = json.writeValueAsString(frame);
        } catch (IOException e) {
            return;
        }

        List<UUID> otherMembers = jdbc.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND user_id <> ? AND left_at IS NULL",
                (rs, i) -> rs.getObject("user_id", UUID.class),
                convId, managed.userId);
        for (UUID recipient : otherMembers) {
            registry.sendToUser(recipient, serialized);
        }
    }

    /**
     * Chuyển tiếp báo hiệu WebRTC (call.offer / call.answer / call.ice) theo quy tắc khóa chặt 2 thiết bị (D-10):
     * - Yêu cầu phiên JWT/thiết bị còn hiệu lực.
     * - Cuộc gọi phải ở trạng thái ACTIVE và cả hai bên còn liveness lease hợp lệ.
     * - Chỉ chuyển tiếp giữa đúng cặp (caller_id/caller_device_id_hash <-> callee_id/callee_device_id_hash)
     *   qua registry.sendToDevice(...), không ghi sync_events (syncEventId = null); mọi thiết bị thứ 3 bị bỏ qua lặng lẽ.
     */
    private void handleCallSignaling(ManagedWsSession managed, String type, JsonNode payload) {
        Instant now = clock.instant();
        if (!now.isBefore(managed.tokenExpiresAt) || !isDeviceStillValid(managed)) {
            registry.closeSafe(managed, WebSocketSessionRegistry.CLOSE_SESSION_EXPIRED);
            return;
        }
        if (payload == null || !payload.isObject()) {
            return;
        }
        UUID callId = parseOptionalUuid(payload.path("callId").asText(null));
        if (callId == null) {
            return;
        }

        // Xác thực trường tối thiểu theo loại khung: offer/answer cần sdp; ice cần candidate.
        if ("call.offer".equals(type) || "call.answer".equals(type)) {
            String sdp = payload.path("sdp").asText(null);
            if (sdp == null || sdp.isBlank()) {
                return;
            }
        } else {
            JsonNode candidateNode = payload.path("candidate");
            if (candidateNode.isMissingNode() || candidateNode.isNull()
                    || (candidateNode.isTextual() && candidateNode.asText().isBlank())) {
                return;
            }
        }

        CallSignalingInfo call = callService.findActiveCallForSignaling(callId);
        if (call == null || !call.isActiveWithLiveLeases(now)) {
            return;
        }

        UUID targetUserId;
        String targetDeviceHash;
        if (managed.userId.equals(call.callerId()) && Objects.equals(managed.deviceIdHash, call.callerDeviceIdHash())) {
            targetUserId = call.calleeId();
            targetDeviceHash = call.calleeDeviceIdHash();
        } else if (managed.userId.equals(call.calleeId()) && Objects.equals(managed.deviceIdHash, call.calleeDeviceIdHash())) {
            targetUserId = call.callerId();
            targetDeviceHash = call.callerDeviceIdHash();
        } else {
            // Thiết bị thứ 3 hoặc người ngoài cuộc gọi -> bỏ qua lặng lẽ.
            return;
        }

        if (targetDeviceHash == null || targetDeviceHash.isBlank()) {
            return;
        }

        try {
            WsFrame frame = new WsFrame(1, type, UUID.randomUUID().toString(), null, payload);
            String frameJson = json.writeValueAsString(frame);
            registry.sendToDevice(targetUserId, targetDeviceHash, frameJson);
        } catch (IOException ignored) {
            // Bỏ qua lỗi tuần tự hóa mà không ảnh hưởng phiên WebSocket.
        }
    }

    /**
     * Xử lý khung call.keepalive gửi định kỳ từ thiết bị đang đàm thoại ACTIVE:
     * kiểm tra JWT/thiết bị, giới hạn tối đa 1 lần cập nhật / 10 giây / thiết bị,
     * gia hạn riêng deadline của bên gửi lên now + 60s dưới khóa dòng calls (không ghi sync_events).
     */
    private void handleCallKeepalive(ManagedWsSession managed, JsonNode payload) {
        Instant now = clock.instant();
        if (!now.isBefore(managed.tokenExpiresAt) || !isDeviceStillValid(managed)) {
            registry.closeSafe(managed, WebSocketSessionRegistry.CLOSE_SESSION_EXPIRED);
            return;
        }
        if (payload == null || !payload.isObject()) {
            return;
        }
        UUID callId = parseOptionalUuid(payload.path("callId").asText(null));
        if (callId == null) {
            return;
        }

        String throttleKey = callId + ":" + managed.userId + ":" + managed.deviceIdHash;
        Instant prev = lastCallKeepaliveUpdated.get(throttleKey);
        if (prev != null && Duration.between(prev, now).compareTo(CALL_KEEPALIVE_THROTTLE) < 0) {
            return;
        }
        // Ghi mốc throttle trước/ngay cả khi gia hạn thất bại để chặn spam SELECT ... FOR UPDATE trên callId không hợp lệ.
        lastCallKeepaliveUpdated.put(throttleKey, now);
        if (lastCallKeepaliveUpdated.size() > 1000) {
            Instant cutoff = now.minus(CALL_KEEPALIVE_THROTTLE);
            lastCallKeepaliveUpdated.entrySet().removeIf(e -> !e.getValue().isAfter(cutoff));
        }

        callService.renewActiveCallLease(callId, managed.userId, managed.deviceIdHash);
    }

    private static UUID parseOptionalUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Kiểm tra thiết bị vẫn chưa bị thu hồi, token vẫn mới hơn token_valid_after và user còn ACTIVE. */
    private boolean isDeviceStillValid(ManagedWsSession managed) {
        List<Boolean> rows = jdbc.query(
                "SELECT d.token_valid_after, d.revoked_at, u.status "
                        + "FROM user_devices d JOIN users u ON u.id = d.user_id "
                        + "WHERE d.id = ? AND d.user_id = ?",
                (rs, i) -> {
                    boolean notRevoked = rs.getTimestamp("revoked_at") == null;
                    boolean active = "ACTIVE".equals(rs.getString("status"));
                    boolean fresh = managed.issuedAtMs >= rs.getTimestamp("token_valid_after").toInstant().toEpochMilli();
                    return notRevoked && active && fresh;
                },
                managed.deviceRowId, managed.userId);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }
}
