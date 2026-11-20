package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý biên nhận DELIVERED và READ đơn điệu theo ADR-010 (D-11):
 * mốc biên nhận không bao giờ lùi và luôn bảo đảm bất biến last_delivered_message_id >= last_read_message_id.
 */
@Service
public class ReceiptService {
    private final JdbcTemplate jdbc;
    private final SyncEventWriter syncEvents;

    public ReceiptService(JdbcTemplate jdbc, SyncEventWriter syncEvents) {
        this.jdbc = jdbc;
        this.syncEvents = syncEvents;
    }

    /**
     * Nâng mốc đã nhận (DELIVERED) lên tới upToMessageId; bỏ qua im lặng nếu không tăng thực sự (idempotent).
     */
    @Transactional
    public void markDelivered(UUID actorId, UUID conversationId, Long upToMessageId) {
        if (upToMessageId == null || upToMessageId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "upToMessageId must be > 0");
        }
        MemberWatermarks member = requireActiveMember(conversationId, actorId);
        long maxMessageId = maxMessageIdInConversation(conversationId);
        long effectiveUpTo = Math.min(upToMessageId, maxMessageId);

        // Không cập nhật và không phát thêm RECEIPT_UPDATED nếu mốc mới không vượt quá mốc hiện có hoặc mốc nhìn thấy.
        if (effectiveUpTo <= 0
                || effectiveUpTo <= member.lastDeliveredId
                || effectiveUpTo <= member.visibleFromId) {
            return;
        }

        jdbc.update(
                "UPDATE conversation_members "
                        + "SET last_delivered_message_id = GREATEST(last_delivered_message_id, ?) "
                        + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                effectiveUpTo, conversationId, actorId);

        MemberWatermarks updated = requireActiveMember(conversationId, actorId);
        if (updated.lastDeliveredId > member.lastDeliveredId) {
            emitReceiptUpdated(conversationId, actorId, updated.lastDeliveredId, updated.lastReadId);
        }
    }

    /**
     * Nâng mốc đã đọc (READ) lên tới upToMessageId; đồng thời tự nâng last_delivered_message_id >= last_read_message_id.
     */
    @Transactional
    public void markRead(UUID actorId, UUID conversationId, Long upToMessageId) {
        if (upToMessageId == null || upToMessageId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "upToMessageId must be > 0");
        }
        MemberWatermarks member = requireActiveMember(conversationId, actorId);
        long maxMessageId = maxMessageIdInConversation(conversationId);
        long effectiveUpTo = Math.min(upToMessageId, maxMessageId);

        // Nếu cả READ lẫn DELIVERED đều đã >= effectiveUpTo thì trả 204 mà không ghi thừa sự kiện.
        if (effectiveUpTo <= 0
                || effectiveUpTo <= member.visibleFromId
                || (effectiveUpTo <= member.lastReadId && effectiveUpTo <= member.lastDeliveredId)) {
            return;
        }

        // Cập nhật đồng thời cả hai mốc trong 1 câu SQL để bảo toàn tuyệt đối ràng buộc CHECK delivered >= read.
        jdbc.update(
                "UPDATE conversation_members "
                        + "SET last_read_message_id = GREATEST(last_read_message_id, ?), "
                        + "    last_delivered_message_id = GREATEST(last_delivered_message_id, last_read_message_id, ?) "
                        + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                effectiveUpTo, effectiveUpTo, conversationId, actorId);

        MemberWatermarks updated = requireActiveMember(conversationId, actorId);
        if (updated.lastReadId > member.lastReadId || updated.lastDeliveredId > member.lastDeliveredId) {
            emitReceiptUpdated(conversationId, actorId, updated.lastDeliveredId, updated.lastReadId);
        }
    }

    private void emitReceiptUpdated(UUID conversationId, UUID actorId, long deliveredUpTo, long readUpTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("userId", actorId.toString());
        payload.put("deliveredUpTo", deliveredUpTo);
        payload.put("readUpTo", readUpTo);

        String entityId = conversationId + ":" + actorId;
        List<UUID> activeMembers = jdbc.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL",
                (rs, i) -> rs.getObject("user_id", UUID.class),
                conversationId);
        for (UUID recipient : activeMembers) {
            syncEvents.write(recipient, "RECEIPT_UPDATED", "RECEIPT", entityId, payload);
        }
    }

    private MemberWatermarks requireActiveMember(UUID conversationId, UUID userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id = ?)",
                Boolean.class,
                conversationId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        List<MemberWatermarks> rows = jdbc.query(
                "SELECT visible_from_message_id, last_delivered_message_id, last_read_message_id, (left_at IS NOT NULL) AS left_flag "
                        + "FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                (rs, i) -> new MemberWatermarks(
                        rs.getLong("visible_from_message_id"),
                        rs.getLong("last_delivered_message_id"),
                        rs.getLong("last_read_message_id"),
                        rs.getBoolean("left_flag")),
                conversationId, userId);
        if (rows.isEmpty() || rows.get(0).left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }
        return rows.get(0);
    }

    private long maxMessageIdInConversation(UUID conversationId) {
        Long maxId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM messages WHERE conversation_id = ?",
                Long.class,
                conversationId);
        return maxId == null ? 0L : maxId;
    }

    private record MemberWatermarks(long visibleFromId, long lastDeliveredId, long lastReadId, boolean left) {
    }
}
