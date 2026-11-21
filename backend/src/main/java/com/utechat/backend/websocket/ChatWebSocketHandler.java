package com.utechat.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.MessagingDtos.WsFrame;
import com.utechat.backend.websocket.WebSocketSessionRegistry.ManagedWsSession;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * và chuyển tiếp typing.start / typing.stop có giới hạn 1 lần / 3 giây cho thành viên đang hoạt động.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Duration TYPING_THROTTLE = Duration.ofSeconds(3);
    private static final Duration DB_TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final WebSocketSessionRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastTypingSent = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(
            WebSocketSessionRegistry registry,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
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
            default -> {
                // Bỏ qua an toàn các loại khung không thuộc Phase 5.
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
