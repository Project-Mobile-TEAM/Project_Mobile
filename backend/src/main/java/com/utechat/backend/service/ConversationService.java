package com.utechat.backend.service;

import com.utechat.backend.dto.MessagingDtos.CallSummaryDto;
import com.utechat.backend.dto.MessagingDtos.ConversationDto;
import com.utechat.backend.dto.MessagingDtos.MemberDto;
import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ quản lý hội thoại (Phase 5 Plan 05-01: mở/tạo chat 1-1, danh sách và chi tiết hội thoại).
 * Các thao tác nhóm (tạo nhóm, đổi tên, quản lý thành viên, bàn giao ADMIN) được mở rộng ở Plan 05-02.
 */
@Service
public class ConversationService {
    private final JdbcTemplate jdbc;
    private final ContactPolicyService policy;
    private final SyncEventWriter syncEvents;

    public ConversationService(JdbcTemplate jdbc, ContactPolicyService policy, SyncEventWriter syncEvents) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.syncEvents = syncEvents;
    }

    /**
     * Mở lại hội thoại 1-1 sẵn có hoặc tạo mới theo direct_key duy nhất (minUuid:maxUuid).
     * Luôn khóa cặp bằng lockPair trước và kiểm tra 403 BLOCKED nghiêm ngặt trước 403 CONTACT_REQUIRED.
     */
    @Transactional
    public ConversationDto openOrCreateDirect(UUID actorId, String rawPeerUserId) {
        UUID peerId = parseRequiredUuid(rawPeerUserId, "Invalid userId");
        if (actorId.equals(peerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF", "Cannot start direct chat with yourself");
        }

        // Khóa cặp không phân biệt chiều trước khi đọc/tạo để chống race condition tạo trùng direct_key.
        policy.lockPair(actorId, peerId);

        // Kiểm tra cả hai tài khoản đều đang ACTIVE; không lộ thông tin tài khoản đã xóa.
        if (!isActiveUser(actorId) || !isActiveUser(peerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
        }

        // Chính sách D-01: luôn kiểm tra BLOCKED trước CONTACT_REQUIRED ở cả mở chat lẫn gửi tin.
        enforceDirectPolicy(actorId, peerId);

        String directKey = buildDirectKey(actorId, peerId);
        List<UUID> existing = jdbc.query(
                "SELECT id FROM conversations WHERE direct_key = ?",
                (rs, i) -> rs.getObject("id", UUID.class),
                directKey);

        UUID conversationId;
        boolean newlyCreated = false;
        if (!existing.isEmpty()) {
            conversationId = existing.get(0);
            // Bảo đảm cả hai thành viên đều có dòng trong conversation_members mà không tạo thêm sự kiện trùng.
            ensureDirectMembers(conversationId, actorId, peerId);
        } else {
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO conversations (type, direct_key, created_by) VALUES ('DIRECT', ?, ?) "
                            + "ON CONFLICT (direct_key) DO NOTHING RETURNING id",
                    (rs, i) -> rs.getObject("id", UUID.class),
                    directKey, actorId);
            if (!inserted.isEmpty()) {
                conversationId = inserted.get(0);
                newlyCreated = true;
            } else {
                // Phòng trường hợp hiếm có transaction khác vừa commit ngay trước đó.
                conversationId = jdbc.queryForObject(
                        "SELECT id FROM conversations WHERE direct_key = ?",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        directKey);
            }
            ensureDirectMembers(conversationId, actorId, peerId);
        }

        ConversationDto actorView = buildConversationDto(actorId, conversationId);
        if (newlyCreated) {
            // Chỉ phát CONVERSATION_UPDATED khi hội thoại 1-1 thực sự mới được tạo.
            syncEvents.write(actorId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), actorView);
            ConversationDto peerView = buildConversationDto(peerId, conversationId);
            syncEvents.write(peerId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), peerView);
        }
        return actorView;
    }

    /**
     * Lấy danh sách các hội thoại mà người dùng đang là thành viên hoạt động (left_at IS NULL),
     * sắp xếp theo tin nhắn mới nhất giảm dần.
     */
    @Transactional(readOnly = true)
    public List<ConversationDto> listConversations(UUID actorId) {
        List<UUID> ids = jdbc.query(
                "SELECT c.id FROM conversations c "
                        + "JOIN conversation_members cm ON cm.conversation_id = c.id "
                        + "WHERE cm.user_id = ? AND cm.left_at IS NULL "
                        + "ORDER BY COALESCE(c.last_message_id, 0) DESC, c.created_at DESC",
                (rs, i) -> rs.getObject("id", UUID.class),
                actorId);
        List<ConversationDto> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            result.add(buildConversationDto(actorId, id));
        }
        return result;
    }

    /**
     * Lấy chi tiết một hội thoại; từ chối 404 nếu hội thoại không tồn tại và 403 NOT_A_MEMBER nếu không còn tham gia.
     */
    @Transactional(readOnly = true)
    public ConversationDto getConversation(UUID actorId, UUID conversationId) {
        if (!conversationExists(conversationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        List<Boolean> active = jdbc.query(
                "SELECT (left_at IS NULL) AS active FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                (rs, i) -> rs.getBoolean("active"),
                conversationId, actorId);
        if (active.isEmpty() || !Boolean.TRUE.equals(active.get(0))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }
        return buildConversationDto(actorId, conversationId);
    }

    /**
     * Dựng ConversationDto theo góc nhìn của viewerId (tái sử dụng cho Plan 05-02 và SyncService),
     * áp dụng chặt bất đẳng thức m.id > visible_from_message_id cho lastMessage và unreadCount.
     */
    public ConversationDto buildConversationDto(UUID viewerId, UUID conversationId) {
        List<ConversationHeader> headers = jdbc.query(
                "SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id, c.created_at, "
                        + "cm.muted, cm.visible_from_message_id, cm.last_read_message_id, cm.left_at "
                        + "FROM conversations c "
                        + "JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.user_id = ? "
                        + "WHERE c.id = ?",
                (rs, i) -> new ConversationHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("avatar_url"),
                        rs.getObject("last_message_id") == null ? null : rs.getLong("last_message_id"),
                        toIso(rs.getTimestamp("created_at")),
                        rs.getBoolean("muted"),
                        rs.getLong("visible_from_message_id"),
                        rs.getLong("last_read_message_id"),
                        toIso(rs.getTimestamp("left_at"))),
                viewerId, conversationId);
        if (headers.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        ConversationHeader h = headers.get(0);

        List<MemberDto> members = jdbc.query(
                "SELECT cm.user_id, u.display_name, u.avatar_url, cm.role, cm.visible_from_message_id, "
                        + "cm.last_delivered_message_id, cm.last_read_message_id, cm.muted, cm.left_at "
                        + "FROM conversation_members cm "
                        + "JOIN users u ON u.id = cm.user_id "
                        + "WHERE cm.conversation_id = ? "
                        + "ORDER BY cm.joined_at ASC, cm.user_id ASC",
                (rs, i) -> new MemberDto(
                        rs.getObject("user_id", UUID.class).toString(),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getString("role"),
                        rs.getLong("visible_from_message_id"),
                        rs.getLong("last_delivered_message_id"),
                        rs.getLong("last_read_message_id"),
                        rs.getBoolean("muted"),
                        toIso(rs.getTimestamp("left_at"))),
                conversationId);

        // Chỉ hiển thị lastMessage nếu nằm sau mốc visible_from_message_id (dấu > chặt).
        Long visibleLastMessageId = null;
        MessageDto lastMessage = null;
        if (h.lastMessageId != null && h.lastMessageId > h.visibleFromMessageId) {
            visibleLastMessageId = h.lastMessageId;
            lastMessage = loadMessageById(h.lastMessageId);
        }

        // Số tin chưa đọc chỉ tính các tin của người khác lớn hơn cả last_read và visible_from.
        long unreadFloor = Math.max(h.lastReadMessageId, h.visibleFromMessageId);
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM messages m "
                        + "WHERE m.conversation_id = ? AND m.sender_id <> ? AND m.id > ?",
                Integer.class,
                conversationId, viewerId, unreadFloor);

        return new ConversationDto(
                h.id.toString(),
                h.type,
                h.title,
                h.avatarUrl,
                visibleLastMessageId,
                h.createdAt,
                unread == null ? 0 : unread,
                h.muted,
                h.visibleFromMessageId,
                h.leftAt,
                lastMessage,
                members);
    }

    /** Kiểm tra chính sách nhắn tin 1-1: 403 BLOCKED luôn được ưu tiên trước 403 CONTACT_REQUIRED. */
    void enforceDirectPolicy(UUID actorId, UUID peerId) {
        if (policy.isBlockedEitherWay(actorId, peerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "User is blocked");
        }
        Boolean allowStrangers = jdbc.queryForObject(
                "SELECT allow_messages_from_non_contacts FROM users WHERE id = ?",
                Boolean.class,
                peerId);
        if (!Boolean.TRUE.equals(allowStrangers) && !policy.areAcceptedFriends(actorId, peerId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CONTACT_REQUIRED",
                    "Recipient only accepts messages from accepted contacts");
        }
    }

    /** Chèn đủ hai dòng thành viên cho hội thoại 1-1 một cách idempotent. */
    private void ensureDirectMembers(UUID conversationId, UUID actorId, UUID peerId) {
        jdbc.update(
                "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                        + "VALUES (?, ?, 'MEMBER', 0) ON CONFLICT (conversation_id, user_id) DO NOTHING",
                conversationId, actorId);
        jdbc.update(
                "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                        + "VALUES (?, ?, 'MEMBER', 0) ON CONFLICT (conversation_id, user_id) DO NOTHING",
                conversationId, peerId);
    }

    /** Kiểm tra người dùng có tồn tại và ở trạng thái ACTIVE hay không. */
    private boolean isActiveUser(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')",
                Boolean.class,
                userId));
    }

    /** Kiểm tra hội thoại có tồn tại trong CSDL hay không. */
    private boolean conversationExists(UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id = ?)",
                Boolean.class,
                conversationId));
    }

    /** Tải một MessageDto đầy đủ metadata (kèm attachments và calls) theo ID. */
    private MessageDto loadMessageById(long messageId) {
        List<MessageDto> rows = jdbc.query(
                MessageService.SELECT_MESSAGE_WITH_METADATA + " WHERE m.id = ?",
                MessageService::mapMessageRow,
                messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Tạo khóa direct_key chuẩn minUuid:maxUuid (chữ thường) khớp thứ tự UUID PostgreSQL. */
    static String buildDirectKey(UUID a, UUID b) {
        String sa = a.toString().toLowerCase();
        String sb = b.toString().toLowerCase();
        return sa.compareTo(sb) <= 0 ? sa + ":" + sb : sb + ":" + sa;
    }

    /** Parse UUID bắt buộc từ request body; ném 400 VALIDATION nếu sai định dạng. */
    static UUID parseRequiredUuid(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
    }

    /** Chuyển Timestamp SQL sang chuỗi ISO-8601 UTC (trả null nếu chưa có giá trị). */
    static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private record ConversationHeader(
            UUID id,
            String type,
            String title,
            String avatarUrl,
            Long lastMessageId,
            String createdAt,
            boolean muted,
            long visibleFromMessageId,
            long lastReadMessageId,
            String leftAt) {
    }
}
