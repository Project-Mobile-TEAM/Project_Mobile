package com.utechat.backend.service;

import com.utechat.backend.dto.MessagingDtos.CallSummaryDto;
import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.dto.MessagingDtos.SendMessageRequest;
import com.utechat.backend.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ gửi và tải lịch sử tin nhắn (Phase 5 & Phase 6: nhận TEXT/IMAGE/AUDIO/VIDEO/FILE/LOCATION từ client,
 * chống trùng clientId, rate limit 30/10s, kiểm quyền 1-1/nhóm, gắn attachment và bảo toàn metadata CALL/SYSTEM).
 */
@Service
public class MessageService {
    /** Câu SELECT chuẩn kèm LEFT JOIN attachments và calls để bảo toàn trọn vẹn metadata non-TEXT (D-15). */
    static final String SELECT_MESSAGE_WITH_METADATA =
            "SELECT m.id, m.client_id, m.conversation_id, m.sender_id, m.type, m.text, "
                    + "m.attachment_id, a.mime, a.size_bytes, a.original_name, a.duration_ms, a.width, a.height, "
                    + "m.system_event, m.system_params, "
                    + "m.call_id, c.state AS call_state, c.media AS call_media, c.end_reason AS call_end_reason, "
                    + "m.latitude, m.longitude, m.created_at "
                    + "FROM messages m "
                    + "LEFT JOIN attachments a ON a.id = m.attachment_id "
                    + "LEFT JOIN calls c ON c.id = m.call_id";

    /** Các loại tin nhắn được phép gửi trực tiếp từ client (Phase 6 mở rộng D-02, D-04). */
    private static final Set<String> ALLOWED_CLIENT_TYPES =
            Set.of("TEXT", "IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION");

    private static final Pattern ATTACHMENT_URL_PATTERN = Pattern.compile(
            "^/api/media/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    /** Kết quả gửi tin kèm cờ created để Controller trả HTTP 201 (mới tạo) hoặc 200 (trùng clientId). */
    public record SendResult(MessageDto message, boolean created) {
    }

    private final JdbcTemplate jdbc;
    private final ContactPolicyService policy;
    private final ConversationService conversationService;
    private final MessageRateLimiter rateLimiter;
    private final SyncEventWriter syncEvents;

    public MessageService(
            JdbcTemplate jdbc,
            ContactPolicyService policy,
            ConversationService conversationService,
            MessageRateLimiter rateLimiter,
            SyncEventWriter syncEvents) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.syncEvents = syncEvents;
    }

    /**
     * Tải lịch sử tin nhắn phân trang ngược theo cursor beforeId (mới nhất trước),
     * lọc nghiêm ngặt m.id > visible_from_message_id và chỉ cho phép thành viên đang hoạt động.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID actorId, UUID conversationId, Long beforeId, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        if (!conversationExists(conversationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        MemberGuard guard = loadMemberGuard(conversationId, actorId);
        if (guard == null || guard.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }

        if (beforeId != null) {
            return jdbc.query(
                    SELECT_MESSAGE_WITH_METADATA
                            + " WHERE m.conversation_id = ? AND m.id > ? AND m.id < ? "
                            + "ORDER BY m.id DESC LIMIT ?",
                    MessageService::mapMessageRow,
                    conversationId, guard.visibleFromMessageId, beforeId, limit);
        }
        return jdbc.query(
                SELECT_MESSAGE_WITH_METADATA
                        + " WHERE m.conversation_id = ? AND m.id > ? "
                        + "ORDER BY m.id DESC LIMIT ?",
                MessageService::mapMessageRow,
                conversationId, guard.visibleFromMessageId, limit);
    }

    /**
     * Gửi tin nhắn (TEXT, IMAGE, AUDIO, VIDEO, FILE, LOCATION) với cơ chế chống trùng theo (sender_id, client_id).
     * Nhóm khóa hàng conversations FOR UPDATE và kiểm expectedVisibleFromMessageId;
     * 1-1 khóa cặp lockPair và kiểm 403 BLOCKED trước 403 CONTACT_REQUIRED.
     * Tin nhắn tệp đính kèm kiểm tra quyền sở hữu uploader_id, khớp loại tệp (kind == type) và ràng buộc duy nhất uq_messages_attachment.
     */
    @Transactional
    public SendResult sendMessage(UUID actorId, UUID conversationId, SendMessageRequest req) {
        if (req == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Message payload is required");
        }
        UUID clientId = ConversationService.parseRequiredUuid(req.clientId(), "Invalid clientId");

        if (req.type() == null || !ALLOWED_CLIENT_TYPES.contains(req.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid or unsupported message type");
        }
        String type = req.type();

        // Kiểm tra trùng clientId sớm để replay không tốn rate limit và không ghi trùng sync_events.
        SendResult earlyReplay = findExistingByClientId(actorId, conversationId, clientId);
        if (earlyReplay != null) {
            return earlyReplay;
        }

        String text = null;
        UUID attachmentId = null;
        Double latitude = null;
        Double longitude = null;

        if ("TEXT".equals(type)) {
            text = req.text() == null ? "" : req.text().trim();
            if (text.isEmpty() || text.length() > 4000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Message text must be 1..4000 characters");
            }
            if (req.mediaUrl() != null || req.latitude() != null || req.longitude() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "TEXT message must not include mediaUrl or coordinates");
            }
        } else if ("LOCATION".equals(type)) {
            if (req.latitude() == null || req.longitude() == null
                    || !Double.isFinite(req.latitude()) || !Double.isFinite(req.longitude())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Valid finite latitude and longitude are required");
            }
            if (req.latitude() < -90.0 || req.latitude() > 90.0 || req.longitude() < -180.0 || req.longitude() > 180.0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Latitude must be in [-90, 90] and longitude in [-180, 180]");
            }
            if (req.mediaUrl() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "LOCATION message must not include mediaUrl");
            }
            if (req.text() != null) {
                String trimmed = req.text().trim();
                if (trimmed.length() > 4000) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Description text must not exceed 4000 characters");
                }
                text = trimmed.isEmpty() ? null : trimmed;
            }
            latitude = req.latitude();
            longitude = req.longitude();
        } else {
            // IMAGE, AUDIO, VIDEO, FILE
            if (req.mediaUrl() == null || req.mediaUrl().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "mediaUrl is required for " + type);
            }
            Matcher matcher = ATTACHMENT_URL_PATTERN.matcher(req.mediaUrl().trim());
            if (!matcher.matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid mediaUrl format");
            }
            attachmentId = ConversationService.parseRequiredUuid(matcher.group(1), "Invalid mediaUrl format");

            if (req.latitude() != null || req.longitude() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Media message must not include coordinates");
            }
            if (req.text() != null) {
                String trimmed = req.text().trim();
                if (trimmed.length() > 4000) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Caption text must not exceed 4000 characters");
                }
                text = trimmed.isEmpty() ? null : trimmed;
            }

            // Khóa dòng attachment FOR UPDATE để kiểm tra tính sở hữu và chống tranh chấp gắn file đồng thời
            List<AttachmentCheck> atts = jdbc.query(
                    "SELECT id, uploader_id, kind FROM public.attachments WHERE id = ? FOR UPDATE",
                    (rs, i) -> new AttachmentCheck(
                            rs.getObject("id", UUID.class),
                            rs.getObject("uploader_id", UUID.class),
                            rs.getString("kind")),
                    attachmentId);

            if (atts.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found");
            }
            AttachmentCheck att = atts.get(0);

            if (!actorId.equals(att.uploaderId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not authorized to attach media owned by another user");
            }
            if (!type.equalsIgnoreCase(att.kind())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Attachment kind " + att.kind() + " does not match message type " + type);
            }

            // Kiểm tra xem attachment đã bị tin nhắn khác sử dụng hay chưa
            Boolean alreadyAttached = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM public.messages WHERE attachment_id = ?)",
                    Boolean.class,
                    attachmentId);
            if (Boolean.TRUE.equals(alreadyAttached)) {
                // Kiểm tra lại có phải chính tin nhắn này do replay đồng thời hay không
                SendResult concurrentReplay = findExistingByClientId(actorId, conversationId, clientId);
                if (concurrentReplay != null) {
                    return concurrentReplay;
                }
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Attachment already attached to another message");
            }
        }

        List<String> convTypes = jdbc.query(
                "SELECT type FROM conversations WHERE id = ?",
                (rs, i) -> rs.getString("type"),
                conversationId);
        if (convTypes.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        String convType = convTypes.get(0);

        if ("GROUP".equals(convType)) {
            // Khóa hàng hội thoại trước khi đọc membership và cấp phát ID tin nhắn (tuần tự hóa với add/remove/re-add).
            jdbc.queryForList("SELECT id FROM conversations WHERE id = ? FOR UPDATE", conversationId);
            MemberGuard guard = loadMemberGuard(conversationId, actorId);
            if (guard == null || guard.left) {
                throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this group");
            }
            // Với nhóm, client bắt buộc gửi expectedVisibleFromMessageId khớp mốc hiện tại để chặn tin từ lần tham gia cũ.
            if (req.expectedVisibleFromMessageId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "expectedVisibleFromMessageId is required for group messages");
            }
            if (!req.expectedVisibleFromMessageId().equals(guard.visibleFromMessageId)) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CHANGED", "Group membership tenure changed");
            }
        } else {
            MemberGuard guard = loadMemberGuard(conversationId, actorId);
            if (guard == null || guard.left) {
                throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
            }
            List<UUID> peers = jdbc.query(
                    "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND user_id <> ?",
                    (rs, i) -> rs.getObject("user_id", UUID.class),
                    conversationId, actorId);
            if (peers.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recipient not found");
            }
            UUID peerId = peers.get(0);
            policy.lockPair(actorId, peerId);
            Boolean peerActive = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')",
                    Boolean.class,
                    peerId);
            if (!Boolean.TRUE.equals(peerActive)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recipient not found");
            }
            // Kiểm tra 403 BLOCKED trước 403 CONTACT_REQUIRED kể cả khi phòng chat đã mở từ trước.
            conversationService.enforceDirectPolicy(actorId, peerId);
        }

        // Giới hạn tốc độ 30 tin / 10 giây / userId.
        rateLimiter.checkAndRecord(actorId);

        // Chèn tin nhắn chống trùng đồng thời bằng ON CONFLICT (sender_id, client_id) DO NOTHING.
        List<Long> insertedIds = jdbc.query(
                "INSERT INTO messages (conversation_id, sender_id, client_id, type, text, attachment_id, latitude, longitude) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (sender_id, client_id) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong("id"),
                conversationId, actorId, clientId, type, text, attachmentId, latitude, longitude);

        if (insertedIds.isEmpty()) {
            // Một luồng đồng thời vừa chèn cùng clientId; trả lại bản ghi đã có mà không tạo thêm sync_events.
            SendResult concurrentReplay = findExistingByClientId(actorId, conversationId, clientId);
            if (concurrentReplay != null) {
                return concurrentReplay;
            }
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Duplicate clientId conflict");
        }

        long newMessageId = insertedIds.get(0);

        // Cập nhật last_message_id của hội thoại và nâng mốc biên nhận của chính người gửi một cách đơn điệu.
        jdbc.update(
                "UPDATE conversations SET last_message_id = GREATEST(COALESCE(last_message_id, 0), ?) WHERE id = ?",
                newMessageId, conversationId);
        jdbc.update(
                "UPDATE conversation_members "
                        + "SET last_delivered_message_id = GREATEST(last_delivered_message_id, ?), "
                        + "    last_read_message_id = GREATEST(last_read_message_id, ?) "
                        + "WHERE conversation_id = ? AND user_id = ?",
                newMessageId, newMessageId, conversationId, actorId);

        MessageDto dto = jdbc.queryForObject(
                SELECT_MESSAGE_WITH_METADATA + " WHERE m.id = ?",
                MessageService::mapMessageRow,
                newMessageId);

        // Ghi sự kiện MESSAGE_CREATED cho toàn bộ thành viên đang hoạt động (left_at IS NULL) trong cùng transaction.
        List<UUID> activeRecipients = jdbc.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL",
                (rs, i) -> rs.getObject("user_id", UUID.class),
                conversationId);
        for (UUID recipientId : activeRecipients) {
            syncEvents.write(recipientId, "MESSAGE_CREATED", "MESSAGE", String.valueOf(newMessageId), dto);
        }

        return new SendResult(dto, true);
    }

    /** Tìm tin nhắn đã tồn tại theo (sender_id, client_id); ném 409 nếu thuộc hội thoại khác. */
    private SendResult findExistingByClientId(UUID actorId, UUID conversationId, UUID clientId) {
        List<MessageDto> existing = jdbc.query(
                SELECT_MESSAGE_WITH_METADATA + " WHERE m.sender_id = ? AND m.client_id = ?",
                MessageService::mapMessageRow,
                actorId, clientId);
        if (existing.isEmpty()) {
            return null;
        }
        MessageDto found = existing.get(0);
        if (!conversationId.toString().equals(found.conversationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "clientId already used in another conversation");
        }
        return new SendResult(found, false);
    }

    private boolean conversationExists(UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id = ?)",
                Boolean.class,
                conversationId));
    }

    private MemberGuard loadMemberGuard(UUID conversationId, UUID userId) {
        List<MemberGuard> rows = jdbc.query(
                "SELECT visible_from_message_id, (left_at IS NOT NULL) AS left_flag "
                        + "FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                (rs, i) -> new MemberGuard(rs.getLong("visible_from_message_id"), rs.getBoolean("left_flag")),
                conversationId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Ánh xạ 1 dòng kết quả SQL (kèm attachments & calls) sang MessageDto bất biến. */
    static MessageDto mapMessageRow(ResultSet rs, int rowNum) throws SQLException {
        UUID attachmentId = rs.getObject("attachment_id", UUID.class);
        String mediaUrl = attachmentId == null ? null : "/api/media/" + attachmentId;
        UUID callId = rs.getObject("call_id", UUID.class);
        CallSummaryDto call = callId == null
                ? null
                : new CallSummaryDto(
                        callId.toString(),
                        rs.getString("call_state"),
                        rs.getString("call_media"),
                        rs.getString("call_end_reason"));
        Timestamp created = rs.getTimestamp("created_at");
        return new MessageDto(
                rs.getLong("id"),
                rs.getObject("client_id", UUID.class).toString(),
                rs.getObject("conversation_id", UUID.class).toString(),
                rs.getObject("sender_id", UUID.class).toString(),
                rs.getString("type"),
                rs.getString("text"),
                mediaUrl,
                rs.getString("mime"),
                rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"),
                rs.getString("original_name"),
                rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"),
                rs.getObject("width") == null ? null : rs.getInt("width"),
                rs.getObject("height") == null ? null : rs.getInt("height"),
                rs.getString("system_event"),
                rs.getString("system_params"),
                call,
                rs.getObject("latitude") == null ? null : rs.getDouble("latitude"),
                rs.getObject("longitude") == null ? null : rs.getDouble("longitude"),
                created == null ? null : created.toInstant().toString());
    }

    private record MemberGuard(long visibleFromMessageId, boolean left) {
    }

    private record AttachmentCheck(UUID id, UUID uploaderId, String kind) {
    }
}
