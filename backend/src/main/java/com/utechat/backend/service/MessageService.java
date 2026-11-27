package com.utechat.backend.service;

import com.utechat.backend.dto.MessagingDtos.CallSummaryDto;
import com.utechat.backend.dto.MessagingDtos.MessageDeletedForMePayload;
import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.dto.MessagingDtos.MessageRecalledPayload;
import com.utechat.backend.dto.MessagingDtos.MessageReactionUpdatedPayload;
import com.utechat.backend.dto.MessagingDtos.ReactionDto;
import com.utechat.backend.dto.MessagingDtos.ReactionUpdateResponse;
import com.utechat.backend.dto.MessagingDtos.SendMessageRequest;
import com.utechat.backend.dto.MessagingDtos.StickerSummaryDto;
import com.utechat.backend.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
                    + "m.latitude, m.longitude, m.created_at, m.recalled_at, m.sticker_id, "
                    + "s.pack_id AS sticker_pack_id, s.code AS sticker_code, s.label AS sticker_label, "
                    + "s.image_url AS sticker_image_url, s.width AS sticker_width, s.height AS sticker_height "
                    + "FROM messages m "
                    + "LEFT JOIN attachments a ON a.id = m.attachment_id "
                    + "LEFT JOIN calls c ON c.id = m.call_id "
                    + "LEFT JOIN stickers s ON s.id = m.sticker_id";

    /** Các loại tin nhắn được phép gửi trực tiếp từ client (Phase 6 mở rộng D-02, D-04). */
    private static final Set<String> ALLOWED_CLIENT_TYPES =
            Set.of("TEXT", "IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION", "STICKER");

    private static final Set<String> FIXED_EMOJIS = Set.of("❤️", "😂", "👍", "😮", "😢", "😡");
    private static final Duration RECALL_WINDOW = Duration.ofHours(24);

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
    private final MediaService mediaService;
    private final Clock clock;

    public MessageService(
            JdbcTemplate jdbc,
            ContactPolicyService policy,
            ConversationService conversationService,
            MessageRateLimiter rateLimiter,
            SyncEventWriter syncEvents,
            MediaService mediaService,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.syncEvents = syncEvents;
        this.mediaService = mediaService;
        this.clock = clock;
    }

    /**
     * Tải lịch sử tin nhắn phân trang ngược theo cursor beforeId (mới nhất trước),
     * lọc nghiêm ngặt m.id > visible_from_message_id và chỉ cho phép thành viên đang hoạt động.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MessageDto> getMessages(UUID actorId, UUID conversationId, Long beforeId, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        if (!conversationExists(conversationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        MemberGuard guard = loadMemberGuard(conversationId, actorId);
        if (guard == null || guard.leftAt() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }

        // Lọc dấu xóa riêng trong cùng snapshot với trang tin và các biểu cảm.
        List<Object> params = new ArrayList<>(List.of(conversationId, guard.visibleFromMessageId, actorId));
        String beforeClause = "";
        if (beforeId != null) {
            beforeClause = " AND m.id < ?";
            params.add(beforeId);
        }
        params.add(limit);
        List<MessageDto> messages = jdbc.query(SELECT_MESSAGE_WITH_METADATA + """
                 WHERE m.conversation_id = ? AND m.id > ?
                   AND NOT EXISTS (SELECT 1 FROM public.message_deletions d WHERE d.message_id = m.id AND d.user_id = ?)
                """ + beforeClause + " ORDER BY m.id DESC LIMIT ?", MessageService::mapMessageRow, params.toArray());
        Map<Long, List<ReactionDto>> reactions = loadReactions(messages.stream().map(MessageDto::id).toList());
        return messages.stream().map(message -> {
            List<ReactionDto> values = reactions.get(message.id());
            return values == null ? message : message.withReactions(values);
        }).toList();
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
        if (!"STICKER".equals(type) && req.stickerId() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "stickerId requires STICKER type");
        }

        // Kiểm tra trùng clientId sớm để replay không tốn rate limit và không ghi trùng sync_events.
        SendResult earlyReplay = findExistingByClientId(actorId, conversationId, clientId);
        if (earlyReplay != null) {
            return earlyReplay;
        }

        String text = null;
        UUID attachmentId = null;
        UUID stickerId = null;
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
        } else if ("STICKER".equals(type)) {
            stickerId = ConversationService.parseRequiredUuid(req.stickerId(), "Invalid stickerId");
            if (req.text() != null || req.mediaUrl() != null || req.latitude() != null || req.longitude() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "STICKER accepts only stickerId");
            }
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
            if (guard == null || guard.leftAt() != null) {
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
            if (guard == null || guard.leftAt() != null) {
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

        // Khóa tệp sau khóa hội thoại/cặp để gửi tin và gỡ tin giữ cùng thứ tự khóa.
        if (attachmentId != null) {
            SendResult replay = checkAttachment(actorId, conversationId, clientId, type, attachmentId);
            if (replay != null) {
                return replay;
            }
        }

        // Tin xếp hàng có thể gửi sau khi gỡ bộ, nên chỉ kiểm tra mã trong danh mục.
        if (stickerId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.stickers WHERE id = ?)", Boolean.class, stickerId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Sticker not found");
        }

        // Giới hạn tốc độ 30 tin / 10 giây / userId.
        rateLimiter.checkAndRecord(actorId);

        // Chèn tin nhắn chống trùng đồng thời bằng ON CONFLICT (sender_id, client_id) DO NOTHING.
        List<Long> insertedIds = jdbc.query(
                "INSERT INTO messages (conversation_id, sender_id, client_id, type, text, attachment_id, latitude, longitude, sticker_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (sender_id, client_id) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong("id"),
                conversationId, actorId, clientId, type, text, attachmentId, latitude, longitude, stickerId);

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

    /** Gỡ nội dung trong 24 giờ bằng đồng hồ máy chủ và phát một sự kiện cho mỗi thiết bị cần đồng bộ. */
    @Transactional
    public MessageDto recallMessage(UUID actorId, UUID conversationId, long messageId) {
        ActionMessage message = lockActionMessage(actorId, conversationId, messageId, false);
        if (!actorId.equals(message.senderId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the sender may recall the message");
        }
        if ("SYSTEM".equals(message.type()) || "CALL".equals(message.type())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "This message type cannot be recalled");
        }
        if (message.recalledAt() != null) {
            return loadMessage(messageId);
        }
        Instant now = clock.instant();
        if (now.isAfter(message.createdAt().plus(RECALL_WINDOW))) {
            throw new ApiException(HttpStatus.CONFLICT, "RECALL_EXPIRED", "Recall window of 24 hours has expired");
        }

        // Giữ định danh và thứ tự của tin, xóa payload và thu hồi luôn quyền truy cập tệp.
        jdbc.update("""
                UPDATE public.messages
                   SET recalled_at = ?, text = NULL, attachment_id = NULL, latitude = NULL, longitude = NULL, sticker_id = NULL
                 WHERE id = ?
                """, Timestamp.from(now), messageId);
        if (message.attachmentId() != null) {
            mediaService.revokeAttachment(message.attachmentId(), now);
        }
        jdbc.update("DELETE FROM public.message_reactions WHERE message_id = ?", messageId);
        writeActionEvent(conversationId, messageId, "MESSAGE_RECALLED",
                new MessageRecalledPayload(messageId, conversationId.toString(), now.toString(), actorId.toString()));
        return loadMessage(messageId);
    }

    /** Lưu dấu xóa theo tài khoản và không ảnh hưởng lịch sử của người khác trong phòng. */
    @Transactional
    public void deleteMessageForMe(UUID actorId, UUID conversationId, long messageId) {
        lockActionMessage(actorId, conversationId, messageId, true);
        Instant now = clock.instant();
        int inserted = jdbc.update("""
                INSERT INTO public.message_deletions (user_id, message_id, deleted_at) VALUES (?, ?, ?)
                ON CONFLICT (user_id, message_id) DO NOTHING
                """, actorId, messageId, Timestamp.from(now));
        if (inserted > 0) {
            syncEvents.write(actorId, "MESSAGE_DELETED_FOR_ME", "MESSAGE", Long.toString(messageId),
                    new MessageDeletedForMePayload(messageId, conversationId.toString(), now.toString()));
        }
    }

    /** Đổi hoặc bỏ biểu cảm dưới khóa tin nhắn để đồng bộ đúng thứ tự với gỡ tin. */
    @Transactional
    public ReactionUpdateResponse toggleReaction(UUID actorId, UUID conversationId, long messageId, String rawEmoji) {
        String emoji = "❤".equals(rawEmoji) ? "❤️" : rawEmoji;
        if (emoji != null && !FIXED_EMOJIS.contains(emoji)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Unsupported reaction");
        }
        ActionMessage message = lockActionMessage(actorId, conversationId, messageId, false);
        if ("SYSTEM".equals(message.type()) || message.recalledAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "This message cannot receive reactions");
        }
        List<String> existing = jdbc.queryForList(
                "SELECT emoji FROM public.message_reactions WHERE message_id = ? AND user_id = ?",
                String.class, messageId, actorId);
        Instant now = clock.instant();
        String action = "SET";
        if (emoji == null || existing.contains(emoji)) {
            // Bấm lại cùng biểu cảm hay gửi null đều bỏ hàng hiện tại.
            int removed = jdbc.update(
                    "DELETE FROM public.message_reactions WHERE message_id = ? AND user_id = ?", messageId, actorId);
            if (removed > 0) {
                writeActionEvent(conversationId, messageId, "MESSAGE_REACTION_UPDATED",
                        new MessageReactionUpdatedPayload(messageId, conversationId.toString(),
                                actorId.toString(), null, now.toString()));
            }
            action = "REMOVED";
            emoji = null;
        } else {
            jdbc.update("""
                    INSERT INTO public.message_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (message_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = EXCLUDED.created_at
                    """, messageId, actorId, emoji, Timestamp.from(now));
            writeActionEvent(conversationId, messageId, "MESSAGE_REACTION_UPDATED",
                    new MessageReactionUpdatedPayload(messageId, conversationId.toString(),
                            actorId.toString(), emoji, now.toString()));
        }
        return new ReactionUpdateResponse(messageId, emoji, action,
                loadReactions(List.of(messageId)).getOrDefault(messageId, List.of()));
    }

    /** Kiểm quyền và khóa theo thứ tự hội thoại/cặp rồi đến tin nhắn. */
    private ActionMessage lockActionMessage(UUID actorId, UUID conversationId, long messageId, boolean privateDelete) {
        List<String> types = jdbc.queryForList(
                "SELECT type FROM public.conversations WHERE id = ?", String.class, conversationId);
        if (types.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        boolean group = "GROUP".equals(types.get(0));
        if (group) {
            jdbc.queryForList("SELECT id FROM public.conversations WHERE id = ? FOR UPDATE", conversationId);
        }
        MemberGuard guard = loadMemberGuard(conversationId, actorId);
        if (guard == null || (!privateDelete && guard.leftAt() != null)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }
        if (!group && !privateDelete) {
            List<UUID> peers = jdbc.queryForList(
                    "SELECT user_id FROM public.conversation_members WHERE conversation_id = ? AND user_id <> ?",
                    UUID.class, conversationId, actorId);
            if (peers.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recipient not found");
            }
            UUID peer = peers.get(0);
            policy.lockPair(actorId, peer);
            if (policy.isBlockedEitherWay(actorId, peer)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "Conversation is blocked");
            }
        }

        // Tin chỉ có thể được thao tác trong khoảng lịch sử thành viên từng thấy.
        List<ActionMessage> rows = jdbc.query("""
                SELECT m.sender_id, m.type, m.attachment_id, m.recalled_at, m.created_at
                  FROM public.messages m
                 WHERE m.id = ? AND m.conversation_id = ? AND m.id > ?
                   AND (CAST(? AS timestamptz) IS NULL OR m.created_at <= ?)
                 FOR UPDATE OF m
                """, (rs, i) -> new ActionMessage(
                        rs.getObject("sender_id", UUID.class), rs.getString("type"),
                        rs.getObject("attachment_id", UUID.class), rs.getTimestamp("recalled_at"),
                        rs.getTimestamp("created_at").toInstant()),
                messageId, conversationId, guard.visibleFromMessageId(), guard.leftAt(), guard.leftAt());
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Message not found");
        }
        // Đọc dấu xóa sau khi lấy khóa để thấy giao dịch xóa vừa hoàn tất khi hai thiết bị đua nhau.
        if (!privateDelete && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.message_deletions WHERE message_id = ? AND user_id = ?)
                """, Boolean.class, messageId, actorId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Message not found");
        }
        return rows.get(0);
    }

    /** Chỉ phân phối tới thành viên có quyền xem tin, không lộ lịch sử trước khi tham gia nhóm. */
    private void writeActionEvent(UUID conversationId, long messageId, String eventType, Object payload) {
        List<UUID> recipients = jdbc.queryForList("""
                SELECT cm.user_id FROM public.conversation_members cm
                 WHERE cm.conversation_id = ? AND cm.left_at IS NULL AND ? > cm.visible_from_message_id
                   AND NOT EXISTS (
                       SELECT 1 FROM public.message_deletions d WHERE d.message_id = ? AND d.user_id = cm.user_id)
                """, UUID.class, conversationId, messageId, messageId);
        for (UUID recipient : recipients) {
            syncEvents.write(recipient, eventType, "MESSAGE", Long.toString(messageId), payload);
        }
    }

    /** Truy vấn gộp tối đa một trang tin để lịch sử không chạy một câu SQL cho mỗi tin. */
    private Map<Long, List<ReactionDto>> loadReactions(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReactionDto>> grouped = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT message_id, user_id, emoji, created_at
                  FROM public.message_reactions WHERE message_id IN (:ids)
                 ORDER BY message_id, created_at, user_id
                """, Map.of("ids", messageIds), rs -> {
            long id = rs.getLong("message_id");
            ReactionDto reaction = new ReactionDto(rs.getString("user_id"), rs.getString("emoji"),
                    rs.getTimestamp("created_at").toInstant().toString());
            grouped.computeIfAbsent(id, key -> new ArrayList<>()).add(reaction);
        });
        return grouped;
    }

    /** Tải lại tin dưới khóa hiện tại để trả đúng trạng thái sau thao tác. */
    private MessageDto loadMessage(long messageId) {
        return jdbc.queryForObject(SELECT_MESSAGE_WITH_METADATA + " WHERE m.id = ?",
                MessageService::mapMessageRow, messageId);
    }

    /** Khóa và kiểm tệp trước khi gửi, gồm tệp đã thu hồi sau khi gửi một tin cũ. */
    private SendResult checkAttachment(UUID actorId, UUID conversationId, UUID clientId, String type, UUID attachmentId) {
        List<AttachmentCheck> attachments = jdbc.query(
                "SELECT uploader_id, kind, revoked_at FROM public.attachments WHERE id = ? FOR UPDATE",
                (rs, i) -> new AttachmentCheck(rs.getObject("uploader_id", UUID.class),
                        rs.getString("kind"), rs.getTimestamp("revoked_at") != null), attachmentId);
        if (attachments.isEmpty() || attachments.get(0).revoked()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found");
        }
        AttachmentCheck attachment = attachments.get(0);
        if (!actorId.equals(attachment.uploaderId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not authorized to attach media owned by another user");
        }
        if (!type.equalsIgnoreCase(attachment.kind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION",
                    "Attachment kind " + attachment.kind() + " does not match message type " + type);
        }
        Boolean alreadyAttached = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.messages WHERE attachment_id = ?)", Boolean.class, attachmentId);
        if (!Boolean.TRUE.equals(alreadyAttached)) {
            return null;
        }
        SendResult replay = findExistingByClientId(actorId, conversationId, clientId);
        if (replay == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Attachment already attached to another message");
        }
        return replay;
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
                "SELECT visible_from_message_id, left_at "
                        + "FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                (rs, i) -> new MemberGuard(rs.getLong("visible_from_message_id"), rs.getTimestamp("left_at")),
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
        Timestamp recalled = rs.getTimestamp("recalled_at");
        String stickerId = rs.getString("sticker_id");
        StickerSummaryDto sticker = stickerId == null || recalled != null ? null : new StickerSummaryDto(
                stickerId, rs.getString("sticker_pack_id"), rs.getString("sticker_code"), rs.getString("sticker_label"),
                rs.getString("sticker_image_url"), rs.getObject("sticker_width", Integer.class),
                rs.getObject("sticker_height", Integer.class));
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
                created == null ? null : created.toInstant().toString(),
                recalled == null ? null : recalled.toInstant().toString(),
                sticker,
                null);
    }

    private record MemberGuard(long visibleFromMessageId, Timestamp leftAt) {
    }

    private record AttachmentCheck(UUID uploaderId, String kind, boolean revoked) {
    }

    private record ActionMessage(UUID senderId, String type, UUID attachmentId, Timestamp recalledAt, Instant createdAt) {
    }
}
