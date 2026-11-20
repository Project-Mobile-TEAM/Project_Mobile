package com.utechat.backend.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.MessagingDtos.WsFrame;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bộ phát sự kiện WebSocket chạy ĐỘC LẬP SAU KHI TRANSACTION POSTGRESQL ĐÃ COMMIT (afterCommit).
 * Chuyển đổi sync_events của cả Phase 4 (Contact) lẫn Phase 5 (Chat/Device) sang khung JSON v1 trên /ws.
 */
@Service
public class SyncEventRealtimeDispatcher {
    /** Bản ghi sự kiện đã được chèn vào sync_events trong transaction chờ commit. */
    public record CommittedSyncEvent(
            long eventId,
            UUID userId,
            String eventType,
            String entityType,
            String entityId,
            Object payload) {
    }

    private final ObjectMapper json;
    private final WebSocketSessionRegistry registry;
    private final JdbcTemplate jdbc;

    public SyncEventRealtimeDispatcher(
            ObjectMapper json,
            WebSocketSessionRegistry registry,
            JdbcTemplate jdbc) {
        this.json = json;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** Đẩy toàn bộ sự kiện đã commit tới các kết nối WebSocket đang mở của người nhận. */
    public void onEventsCommitted(List<CommittedSyncEvent> events) {
        for (CommittedSyncEvent event : events) {
            String wsType = mapEventTypeToWsType(event.eventType());
            if (wsType == null) {
                continue;
            }
            WsFrame frame = new WsFrame(1, wsType, UUID.randomUUID().toString(), event.eventId(), event.payload());
            String serialized;
            try {
                serialized = json.writeValueAsString(frame);
            } catch (JsonProcessingException e) {
                continue;
            }

            if ("DEVICE_REVOKED".equals(event.eventType())) {
                String deviceHash = resolveRevokedDeviceHash(event);
                if (deviceHash != null) {
                    // Gửi khung auth.revoked trước rồi đóng tức thì thiết bị bị thu hồi bằng mã 4401.
                    registry.sendToDevice(event.userId(), deviceHash, serialized);
                    registry.closeDevice(event.userId(), deviceHash, WebSocketSessionRegistry.CLOSE_DEVICE_REVOKED);
                }
            } else {
                registry.sendToUser(event.userId(), serialized);
            }
        }
    }

    /** Ánh xạ tên sự kiện trong sync_events sang trường type của khung WebSocket v1 (API_CONTRACT mục 3). */
    static String mapEventTypeToWsType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "MESSAGE_CREATED" -> "message.new";
            case "RECEIPT_UPDATED" -> "message.status";
            case "CONVERSATION_UPDATED" -> "conversation.updated";
            case "CONTACT_REQUEST_CREATED" -> "contact_request.new";
            case "CONTACT_REQUEST_UPDATED" -> "contact_request.updated";
            case "CONTACT_UPDATED" -> "contact.updated";
            case "DEVICE_REVOKED" -> "auth.revoked";
            default -> null;
        };
    }

    /** Lấy device_id_hash của thiết bị bị thu hồi từ payload hoặc truy vấn user_devices theo entityId. */
    private String resolveRevokedDeviceHash(CommittedSyncEvent event) {
        if (event.payload() instanceof Map<?, ?> map) {
            Object hash = map.get("deviceIdHash");
            if (hash instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        try {
            UUID deviceRowId = UUID.fromString(event.entityId());
            List<String> rows = jdbc.query(
                    "SELECT device_id_hash FROM user_devices WHERE id = ? AND user_id = ?",
                    (rs, i) -> rs.getString("device_id_hash"),
                    deviceRowId, event.userId());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
