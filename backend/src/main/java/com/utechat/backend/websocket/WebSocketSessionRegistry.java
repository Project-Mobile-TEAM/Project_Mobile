package com.utechat.backend.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Sổ đăng ký phiên WebSocket an toàn đa luồng theo (userId, deviceIdHash):
 * bọc mỗi session bằng ConcurrentWebSocketSessionDecorator (tránh TEXT_FULL_WRITING của Tomcat),
 * hỗ trợ gửi frame, đóng tức thì 4401 khi thu hồi/đăng xuất, và dọn phiên idle > 75s hoặc hết hạn JWT.
 */
@Service
public class WebSocketSessionRegistry {
    public static final CloseStatus CLOSE_SESSION_EXPIRED = new CloseStatus(4401, "SESSION_EXPIRED");
    public static final CloseStatus CLOSE_DEVICE_REVOKED = new CloseStatus(4401, "DEVICE_REVOKED");
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(75);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Clock clock;
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>>> byUser =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ManagedWsSession> bySessionId = new ConcurrentHashMap<>();

    public WebSocketSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    /** Đăng ký session mới sau khi handshake thành công và bọc decorator chống ghi đồng thời. */
    public ManagedWsSession register(WebSocketSession rawSession) {
        Map<String, Object> attrs = rawSession.getAttributes();
        UUID userId = (UUID) attrs.get(WsAuthHandshakeInterceptor.ATTR_USER_ID);
        String deviceHash = (String) attrs.get(WsAuthHandshakeInterceptor.ATTR_DEVICE_HASH);
        UUID deviceRowId = (UUID) attrs.get(WsAuthHandshakeInterceptor.ATTR_DEVICE_ROW_ID);
        long issuedAtMs = (Long) attrs.get(WsAuthHandshakeInterceptor.ATTR_ISSUED_AT_MS);
        Instant tokenExpiresAt = (Instant) attrs.get(WsAuthHandshakeInterceptor.ATTR_TOKEN_EXPIRES_AT);

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        Instant now = clock.instant();
        ManagedWsSession managed = new ManagedWsSession(
                rawSession.getId(), safeSession, userId, deviceHash, deviceRowId, issuedAtMs, tokenExpiresAt, now);

        bySessionId.put(rawSession.getId(), managed);
        byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(deviceHash, k -> new CopyOnWriteArraySet<>())
                .add(managed);
        return managed;
    }

    /** Gỡ session khi kết nối đóng hoặc gặp lỗi vận chuyển. */
    public void unregister(WebSocketSession rawSession) {
        ManagedWsSession removed = bySessionId.remove(rawSession.getId());
        if (removed == null) {
            return;
        }
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(removed.userId);
        if (devices == null) {
            return;
        }
        CopyOnWriteArraySet<ManagedWsSession> sessions = devices.get(removed.deviceIdHash);
        if (sessions != null) {
            sessions.remove(removed);
            if (sessions.isEmpty()) {
                devices.remove(removed.deviceIdHash, sessions);
            }
        }
        if (devices.isEmpty()) {
            byUser.remove(removed.userId, devices);
        }
    }

    /** Tra cứu metadata phiên theo sessionId gốc. */
    public ManagedWsSession find(WebSocketSession rawSession) {
        return bySessionId.get(rawSession.getId());
    }

    /** Cập nhật thời điểm hoạt động gần nhất (heartbeat / frame text / pong). */
    public void touch(WebSocketSession rawSession) {
        ManagedWsSession managed = bySessionId.get(rawSession.getId());
        if (managed != null) {
            managed.lastSeen = clock.instant();
        }
    }

    /** Gửi khung JSON tới mọi thiết bị đang mở WebSocket của một người dùng. */
    public void sendToUser(UUID userId, String jsonFrame) {
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(userId);
        if (devices == null) {
            return;
        }
        for (CopyOnWriteArraySet<ManagedWsSession> set : devices.values()) {
            for (ManagedWsSession session : set) {
                sendSafe(session, jsonFrame);
            }
        }
    }

    /** Gửi khung JSON tới đúng thiết bị cụ thể (ví dụ gửi auth.revoked trước khi đóng 4401). */
    public void sendToDevice(UUID userId, String deviceIdHash, String jsonFrame) {
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(userId);
        if (devices == null || deviceIdHash == null) {
            return;
        }
        CopyOnWriteArraySet<ManagedWsSession> set = devices.get(deviceIdHash);
        if (set == null) {
            return;
        }
        for (ManagedWsSession session : set) {
            sendSafe(session, jsonFrame);
        }
    }

    /** Đóng toàn bộ socket của một thiết bị cụ thể (thu hồi thiết bị, đăng xuất). */
    public void closeDevice(UUID userId, String deviceIdHash, CloseStatus status) {
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(userId);
        if (devices == null || deviceIdHash == null) {
            return;
        }
        CopyOnWriteArraySet<ManagedWsSession> set = devices.get(deviceIdHash);
        if (set == null) {
            return;
        }
        for (ManagedWsSession session : set) {
            closeSafe(session, status);
        }
    }

    /** Đóng mọi thiết bị khác của user trừ thiết bị hiện tại (đổi mật khẩu / quên mật khẩu). */
    public void closeUserDevicesExcept(UUID userId, String keepDeviceHash, CloseStatus status) {
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(userId);
        if (devices == null) {
            return;
        }
        for (Map.Entry<String, CopyOnWriteArraySet<ManagedWsSession>> entry : devices.entrySet()) {
            if (entry.getKey().equals(keepDeviceHash)) {
                continue;
            }
            for (ManagedWsSession session : entry.getValue()) {
                closeSafe(session, status);
            }
        }
    }

    /** Đóng toàn bộ socket mọi thiết bị của user (lên lịch xóa hoặc xóa vĩnh viễn tài khoản). */
    public void closeAllUserDevices(UUID userId, CloseStatus status) {
        ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedWsSession>> devices = byUser.get(userId);
        if (devices == null) {
            return;
        }
        for (CopyOnWriteArraySet<ManagedWsSession> set : devices.values()) {
            for (ManagedWsSession session : set) {
                closeSafe(session, status);
            }
        }
    }

    /**
     * Tác vụ quét định kỳ mỗi 30 giây (public để test tiêm Clock kiểm chứng không cần sleep 90s):
     * đóng phiên có JWT hết hạn với 4401 và đóng socket quá hạn không có heartbeat > 75 giây.
     */
    @Scheduled(fixedDelay = 30_000)
    public void reapStaleAndExpiredSessions() {
        Instant now = clock.instant();
        for (ManagedWsSession session : bySessionId.values()) {
            if (!now.isBefore(session.tokenExpiresAt)) {
                closeSafe(session, CLOSE_SESSION_EXPIRED);
            } else if (Duration.between(session.lastSeen, now).compareTo(IDLE_TIMEOUT) > 0) {
                closeSafe(session, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }

    private void sendSafe(ManagedWsSession managed, String jsonFrame) {
        if (!managed.session.isOpen()) {
            return;
        }
        try {
            managed.session.sendMessage(new TextMessage(jsonFrame));
        } catch (IOException | IllegalStateException e) {
            closeSafe(managed, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    void closeSafe(ManagedWsSession managed, CloseStatus status) {
        try {
            if (managed.session.isOpen()) {
                managed.session.close(status);
            }
        } catch (IOException | IllegalStateException ignored) {
            // Bỏ qua lỗi khi kết nối phía client đã đóng trước đó.
        }
    }

    /** Trạng thái một kết nối WebSocket đang quản lý. */
    public static final class ManagedWsSession {
        public final String sessionId;
        public final WebSocketSession session;
        public final UUID userId;
        public final String deviceIdHash;
        public final UUID deviceRowId;
        public final long issuedAtMs;
        public final Instant tokenExpiresAt;
        public volatile Instant lastSeen;
        public volatile Instant lastDbTouch;

        ManagedWsSession(
                String sessionId,
                WebSocketSession session,
                UUID userId,
                String deviceIdHash,
                UUID deviceRowId,
                long issuedAtMs,
                Instant tokenExpiresAt,
                Instant now) {
            this.sessionId = sessionId;
            this.session = session;
            this.userId = userId;
            this.deviceIdHash = deviceIdHash;
            this.deviceRowId = deviceRowId;
            this.issuedAtMs = issuedAtMs;
            this.tokenExpiresAt = tokenExpiresAt;
            this.lastSeen = now;
            this.lastDbTouch = Instant.EPOCH;
        }
    }
}
