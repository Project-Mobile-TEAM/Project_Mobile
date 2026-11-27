package com.utechat.data.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.dao.ContactDao;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.dao.ReactionDao;
import com.utechat.data.local.dao.SyncStateDao;
import com.utechat.data.local.dao.TranslationDao;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.ContactEntity;
import com.utechat.data.local.entity.ContactRequestEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.notifications.NotificationHelper;
import com.utechat.data.remote.model.ChatDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bộ áp dụng sự kiện đồng bộ dùng chung, bất biến và lũy đẳng (D-03, D-11, D-12, D-14, D-15):
 * - CHỈ được gọi bên trong db.runInTransaction(...) cho ảnh chụp /api/sync/bootstrap và lô /api/sync.
 * - Tuyệt đối không cung cấp đường ghi trực tiếp từ khung WebSocket để tránh ghi đè dữ liệu mới bằng lô /sync cũ hơn.
 * - Xử lý tường minh cả 8 loại sự kiện (Chat + CALL_UPDATED + Contact) và bảo toàn 100% metadata Phase 6.
 */
public class SyncEventApplier {
    private static final Gson GSON = new Gson();

    private final UserDao userDao;
    private final ContactDao contactDao;
    private final ConversationDao conversationDao;
    private final MessageDao messageDao;
    private final SyncStateDao syncStateDao;
    private final ReactionDao reactionDao;
    private final TranslationDao translationDao;
    @Nullable private final String currentDeviceIdHash;
    @Nullable private final Runnable onSelfDeviceRevoked;
    /** Mốc tăng khi có sự kiện gỡ/xóa/cảm xúc, tránh bỏ nhầm reactions khi chỉ có biên nhận đọc mới. */
    private final AtomicLong reactionEventVersion = new AtomicLong();

    public SyncEventApplier(@NonNull AppDatabase db) {
        this(db, null, null);
    }

    public SyncEventApplier(
            @NonNull AppDatabase db,
            @Nullable String currentDeviceIdHash,
            @Nullable Runnable onSelfDeviceRevoked) {
        this.userDao = db.userDao();
        this.contactDao = db.contactDao();
        this.conversationDao = db.conversationDao();
        this.messageDao = db.messageDao();
        this.syncStateDao = db.syncStateDao();
        this.reactionDao = db.reactionDao();
        this.translationDao = db.translationDao();
        this.currentDeviceIdHash = currentDeviceIdHash;
        this.onSelfDeviceRevoked = onSelfDeviceRevoked;
    }

    /** Mốc phiên bản cho luồng cảm xúc/gỡ/xóa ở trang lịch sử. */
    public long reactionEventVersion() {
        return reactionEventVersion.get();
    }

    /**
     * Suy luận trạng thái biên nhận tất định (READ > DELIVERED > SENT) từ mốc của các thành viên (ADR-010, D-11).
     */
    @NonNull
    public static String deriveMessageStatus(
            @Nullable ChatDtos.MessageDto dto,
            @NonNull String myUserId,
            @Nullable List<ConversationMemberEntity> members) {
        if (dto == null || members == null || members.isEmpty()) {
            return "SENT";
        }
        if (myUserId.equals(dto.senderId)) {
            boolean anyDelivered = false;
            for (ConversationMemberEntity member : members) {
                if (member == null || myUserId.equals(member.getUserId())) continue;
                if (member.getLastReadId() >= dto.id) {
                    return "READ";
                }
                if (member.getLastDeliveredId() >= dto.id) {
                    anyDelivered = true;
                }
            }
            return anyDelivered ? "DELIVERED" : "SENT";
        } else {
            for (ConversationMemberEntity member : members) {
                if (member != null && myUserId.equals(member.getUserId())) {
                    if (dto.id <= member.getLastReadId()) return "READ";
                    if (dto.id <= member.getLastDeliveredId()) return "DELIVERED";
                    break;
                }
            }
            return "SENT";
        }
    }

    /**
     * Đóng gói trọn vẹn mọi trường metadata của tin nhắn không phải TEXT (media, call, location, system)
     * vào chuỗi JSON duy nhất lưu ở MessageEntity.attachmentJson, không hạ cấp loại tin về TEXT (D-15).
     */
    @Nullable
    public static String serializeMessageMetadata(@Nullable ChatDtos.MessageDto dto) {
        if (dto == null || dto.recalledAt != null) return null;
        JsonObject meta = new JsonObject();
        if (dto.mediaUrl != null) meta.addProperty("mediaUrl", dto.mediaUrl);
        if (dto.mime != null) meta.addProperty("mime", dto.mime);
        if (dto.sizeBytes != null) meta.addProperty("sizeBytes", dto.sizeBytes);
        if (dto.fileName != null) meta.addProperty("fileName", dto.fileName);
        if (dto.durationMs != null) meta.addProperty("durationMs", dto.durationMs);
        if (dto.width != null) meta.addProperty("width", dto.width);
        if (dto.height != null) meta.addProperty("height", dto.height);
        if (dto.systemEvent != null) meta.addProperty("systemEvent", dto.systemEvent);
        if (dto.systemParams != null) meta.addProperty("systemParams", dto.systemParams);
        if (dto.call != null) meta.add("call", GSON.toJsonTree(dto.call));
        if (dto.latitude != null) meta.addProperty("latitude", dto.latitude);
        if (dto.longitude != null) meta.addProperty("longitude", dto.longitude);
        // Dùng cùng JSON metadata cho tin đang gửi và lịch sử đồng bộ.
        if (dto.sticker != null) {
            meta.addProperty("stickerId", dto.sticker.id);
            meta.addProperty("packId", dto.sticker.packId);
            meta.addProperty("code", dto.sticker.code);
            meta.addProperty("label", dto.sticker.label);
            meta.addProperty("imageUrl", dto.sticker.imageUrl);
            meta.addProperty("width", dto.sticker.width);
            meta.addProperty("height", dto.sticker.height);
        }
        return meta.size() == 0 ? null : GSON.toJson(meta);
    }

    /** Chuyển đổi MessageDto sang MessageEntity sẵn sàng chèn vào Room. */
    @NonNull
    public static MessageEntity toMessageEntity(@NonNull ChatDtos.MessageDto dto, @NonNull String status) {
        MessageEntity entity = new MessageEntity();
        entity.setServerId(dto.id);
        entity.setClientId(dto.clientId != null && !dto.clientId.isEmpty() ? dto.clientId : "srv-" + dto.id);
        entity.setConversationId(dto.conversationId);
        entity.setSenderId(dto.senderId);
        entity.setType(dto.type != null ? dto.type : "TEXT");
        boolean recalled = dto.recalledAt != null;
        entity.setRecalledAt(recalled ? parseIsoMillis(dto.recalledAt, 0L) : null);
        entity.setText(recalled ? null : dto.text);
        entity.setNormalizedText(recalled ? null : MessageText.normalize(dto.text));
        entity.setAttachmentJson(serializeMessageMetadata(dto));
        entity.setCreatedAt(parseIsoMillis(dto.createdAt, System.currentTimeMillis()));
        entity.setStatus(status);
        entity.setErrorCode(null);
        return entity;
    }

    /**
     * Áp dụng toàn bộ ảnh chụp khởi tạo từ GET /api/sync/bootstrap trong cùng một transaction Room:
     * giữ nguyên các tin nhắn cục bộ SENDING/FAILED (serverId IS NULL), đánh dấu left = true cho các nhóm
     * không còn thuộc snapshot và đặt lại sync_state tới snap.syncCursor.
     */
    public void applyBootstrapSnapshot(
            @NonNull String myUserId,
            @NonNull ChatDtos.BootstrapResponse snap,
            long nowMs) {
        // 1) Lưu hồ sơ cá nhân đầy đủ quyền riêng tư.
        if (snap.me != null && snap.me.id != null) {
            UserEntity me = new UserEntity();
            me.setId(snap.me.id);
            me.setPhone(snap.me.phone);
            me.setDisplayName(snap.me.displayName != null ? snap.me.displayName : "");
            me.setAvatarUrl(snap.me.avatarUrl);
            me.setAllowMessagesFromNonContacts(snap.me.allowMessagesFromNonContacts);
            userDao.insert(me);
        }

        // 2) Thay thế nguyên tử ảnh chụp danh bạ (bạn bè + người bị chặn).
        List<ContactEntity> contactEntities = new ArrayList<>();
        List<UserEntity> contactUsers = new ArrayList<>();
        if (snap.friends != null) {
            for (ChatDtos.BootstrapContactDto friend : snap.friends) {
                if (friend == null || friend.id == null) continue;
                contactUsers.add(toUserEntity(friend.id, friend.phone, friend.displayName, friend.avatarUrl));
                ContactEntity ce = new ContactEntity();
                ce.setContactUserId(friend.id);
                ce.setBlocked(false);
                contactEntities.add(ce);
            }
        }
        if (snap.blocked != null) {
            for (ChatDtos.BootstrapContactDto blk : snap.blocked) {
                if (blk == null || blk.id == null) continue;
                contactUsers.add(toUserEntity(blk.id, blk.phone, blk.displayName, blk.avatarUrl));
                ContactEntity ce = new ContactEntity();
                ce.setContactUserId(blk.id);
                ce.setBlocked(true);
                contactEntities.add(ce);
            }
        }
        contactDao.replaceSnapshotContacts(contactEntities, contactUsers, userDao);

        // 3) Thay thế nguyên tử danh sách lời mời kết bạn đang chờ (PENDING).
        List<ContactRequestEntity> reqEntities = new ArrayList<>();
        List<UserEntity> reqUsers = new ArrayList<>();
        if (snap.contactRequests != null) {
            for (ChatDtos.BootstrapContactRequestDto req : snap.contactRequests) {
                if (req == null || req.id == null || req.requester == null || req.addressee == null) continue;
                reqUsers.add(toUserEntity(req.requester.id, null, req.requester.displayName, req.requester.avatarUrl));
                reqUsers.add(toUserEntity(req.addressee.id, null, req.addressee.displayName, req.addressee.avatarUrl));
                ContactRequestEntity cre = new ContactRequestEntity();
                cre.setId(req.id);
                cre.setRequesterId(req.requester.id);
                cre.setAddresseeId(req.addressee.id);
                cre.setStatus(req.status != null ? req.status : "PENDING");
                cre.setCreatedAt(parseIsoMillis(req.createdAt, nowMs));
                reqEntities.add(cre);
            }
        }
        contactDao.replacePendingRequests(reqEntities, reqUsers, userDao);

        // 4) Upsert từng hội thoại đang hoạt động, thành viên và tin nhắn cuối (có suy luận trạng thái biên nhận).
        List<String> activeConversationIds = new ArrayList<>();
        if (snap.conversations != null) {
            for (ChatDtos.ConversationDto conv : snap.conversations) {
                if (conv == null || conv.id == null) continue;
                activeConversationIds.add(conv.id);
                upsertConversationAndMembers(myUserId, conv, nowMs, true);
            }
        }

        // 5) Đánh dấu left = true cho các nhóm cũ không còn trong ảnh chụp bootstrap, giữ lịch sử chỉ đọc (D-12).
        conversationDao.markOtherGroupsLeftIfMissing(activeConversationIds, myUserId);

        // 6) Chốt con trỏ đồng bộ ban đầu trong cùng transaction với dữ liệu snapshot.
        syncStateDao.reset(myUserId, snap.syncCursor, nowMs);
    }

    /** Trạng thái kết thúc cuộc gọi đồng bộ về từ CALL_UPDATED để đóng UI nếu WS rớt đúng lúc kết thúc. */
    public static final class TerminalCallUpdate {
        @NonNull public final String callId;
        @NonNull public final String state;
        @Nullable public final String endReason;

        public TerminalCallUpdate(@NonNull String callId, @NonNull String state, @Nullable String endReason) {
            this.callId = callId;
            this.state = state;
            this.endReason = endReason;
        }
    }

    /** Kết quả đầy đủ của 1 lô /api/sync dùng cho biên nhận DELIVERED, phát thông báo và chốt cuộc gọi sau commit. */
    public static final class SyncBatchOutcome {
        @NonNull public final Map<String, Long> highestIncomingByConversation;
        @NonNull public final List<MessageEntity> newlyInsertedMessages;
        @NonNull public final List<NotificationHelper.MissedCallNotice> missedCalls;
        @NonNull public final List<TerminalCallUpdate> terminalCalls;
        @NonNull public final List<NotificationHelper.ReactionNotice> incomingReactions;

        public SyncBatchOutcome(
                @NonNull Map<String, Long> highestIncomingByConversation,
                @NonNull List<MessageEntity> newlyInsertedMessages,
                @NonNull List<NotificationHelper.MissedCallNotice> missedCalls) {
            this(highestIncomingByConversation, newlyInsertedMessages, missedCalls,
                    Collections.emptyList(), Collections.emptyList());
        }

        public SyncBatchOutcome(
                @NonNull Map<String, Long> highestIncomingByConversation,
                @NonNull List<MessageEntity> newlyInsertedMessages,
                @NonNull List<NotificationHelper.MissedCallNotice> missedCalls,
                @NonNull List<TerminalCallUpdate> terminalCalls) {
            this(highestIncomingByConversation, newlyInsertedMessages, missedCalls,
                    terminalCalls, Collections.emptyList());
        }

        public SyncBatchOutcome(
                @NonNull Map<String, Long> highestIncomingByConversation,
                @NonNull List<MessageEntity> newlyInsertedMessages,
                @NonNull List<NotificationHelper.MissedCallNotice> missedCalls,
                @NonNull List<TerminalCallUpdate> terminalCalls,
                @NonNull List<NotificationHelper.ReactionNotice> incomingReactions) {
            this.highestIncomingByConversation = highestIncomingByConversation;
            this.newlyInsertedMessages = newlyInsertedMessages;
            this.missedCalls = missedCalls;
            this.terminalCalls = terminalCalls;
            this.incomingReactions = incomingReactions;
        }
    }

    /**
     * Áp dụng một lô sự kiện theo thứ tự tăng dần từ GET /api/sync.
     * Trả về bản đồ (conversationId -> maxServerId tin đến từ người khác) để SyncEngine gửi /delivered sau khi commit.
     * Mọi sự kiện không rõ loại hoặc lỗi cấu trúc sẽ ném RuntimeException để rollback cả transaction và giữ nguyên cursor.
     */
    @NonNull
    public Map<String, Long> applyBatch(@NonNull String myUserId, @Nullable List<ChatDtos.SyncEventDto> events) {
        return applyBatchDetailed(myUserId, events).highestIncomingByConversation;
    }

    /**
     * Áp dụng một lô sự kiện và trả thêm danh sách tin nhắn mới chèn cùng thông báo cuộc gọi nhỡ
     * để SyncEngine chỉ bắn thông báo sau khi transaction Room đã commit thành công (D-05, D-08).
     */
    @NonNull
    public SyncBatchOutcome applyBatchDetailed(
            @NonNull String myUserId,
            @Nullable List<ChatDtos.SyncEventDto> events) {
        Map<String, Long> highestIncomingByConversation = new HashMap<>();
        List<MessageEntity> newlyInsertedMessages = new ArrayList<>();
        List<NotificationHelper.MissedCallNotice> missedCalls = new ArrayList<>();
        List<TerminalCallUpdate> terminalCalls = new ArrayList<>();
        List<NotificationHelper.ReactionNotice> incomingReactions = new ArrayList<>();
        Map<String, CallOrigin> callOrigins = new HashMap<>();
        List<PendingMissedCall> pendingMissedCalls = new ArrayList<>();
        Map<String, Long> selfReadUpToInBatch = new HashMap<>();

        if (events == null || events.isEmpty()) {
            return new SyncBatchOutcome(
                    highestIncomingByConversation, newlyInsertedMessages, missedCalls, terminalCalls, incomingReactions);
        }
        for (ChatDtos.SyncEventDto event : events) {
            if (event == null || event.eventType == null) {
                throw new IllegalStateException("SyncEventDto thiếu eventType");
            }
            switch (event.eventType) {
                case "MESSAGE_CREATED":
                    applyMessageCreated(
                            myUserId, event, highestIncomingByConversation, newlyInsertedMessages, callOrigins);
                    break;
                case "CONVERSATION_UPDATED":
                    applyConversationUpdated(myUserId, event);
                    break;
                case "MESSAGE_RECALLED":
                    ChatDtos.MessageRecalledPayload recalled = parseRequired(
                            event.payload, ChatDtos.MessageRecalledPayload.class, event.eventType);
                    applyRecall(recalled.messageId, parseIsoMillis(recalled.recalledAt, 0L));
                    newlyInsertedMessages.removeIf(msg -> Long.valueOf(recalled.messageId).equals(msg.getServerId()));
                    incomingReactions.removeIf(r -> r.messageServerId == recalled.messageId);
                    break;
                case "MESSAGE_DELETED_FOR_ME":
                    ChatDtos.MessageDeletedForMePayload deleted = parseRequired(
                            event.payload, ChatDtos.MessageDeletedForMePayload.class, event.eventType);
                    applyDeleteForMe(deleted.conversationId, deleted.messageId, myUserId);
                    newlyInsertedMessages.removeIf(msg -> Long.valueOf(deleted.messageId).equals(msg.getServerId()));
                    incomingReactions.removeIf(r -> r.messageServerId == deleted.messageId);
                    break;
                case "MESSAGE_REACTION_UPDATED":
                    ChatDtos.MessageReactionUpdatedPayload reaction = parseRequired(
                            event.payload, ChatDtos.MessageReactionUpdatedPayload.class, event.eventType);
                    incomingReactions.removeIf(r ->
                            r.messageServerId == reaction.messageId && r.userId.equals(reaction.userId));
                    NotificationHelper.ReactionNotice notice = applyReactionEvent(myUserId, reaction);
                    if (notice != null) {
                        incomingReactions.add(notice);
                    }
                    break;
                case "RECEIPT_UPDATED":
                    applyReceiptUpdated(myUserId, event, selfReadUpToInBatch);
                    break;
                case "DEVICE_REVOKED":
                    applyDeviceRevoked(event);
                    break;
                case "CALL_UPDATED":
                    applyCallUpdated(myUserId, event, missedCalls, terminalCalls, callOrigins, pendingMissedCalls);
                    break;
                case "CONTACT_REQUEST_CREATED":
                case "CONTACT_REQUEST_UPDATED":
                    applyContactRequestEvent(event);
                    break;
                case "CONTACT_UPDATED":
                    applyContactUpdated(event);
                    break;
                default:
                    // Không âm thầm bỏ qua sự kiện lạ để không tiến nhầm sync cursor (D-03).
                    throw new IllegalStateException("Loại sự kiện đồng bộ không được hỗ trợ: " + event.eventType);
            }
        }

        // Lọc bỏ các tin nhắn mới chèn nhưng đã được chính người dùng đọc ở thiết bị khác trong cùng lô /api/sync.
        if (!selfReadUpToInBatch.isEmpty() && !newlyInsertedMessages.isEmpty()) {
            newlyInsertedMessages.removeIf(msg -> {
                Long readUpTo = selfReadUpToInBatch.get(msg.getConversationId());
                return readUpTo != null && msg.getServerId() != null && msg.getServerId() <= readUpTo;
            });
        }

        // Đối chiếu bổ sung các CALL_UPDATED(MISSED) không có caller khi MESSAGE_CREATED(type=CALL) nằm sau trong cùng lô.
        for (PendingMissedCall pending : pendingMissedCalls) {
            CallOrigin origin = callOrigins.get(pending.callId);
            if (origin != null && origin.callerId != null && !myUserId.equals(origin.callerId)) {
                String convId = pending.conversationId != null ? pending.conversationId : origin.conversationId;
                String name = pending.callerDisplayName != null ? pending.callerDisplayName : origin.callerDisplayName;
                missedCalls.add(new NotificationHelper.MissedCallNotice(pending.callId, convId, origin.callerId, name));
            }
        }

        return new SyncBatchOutcome(
                highestIncomingByConversation, newlyInsertedMessages, missedCalls, terminalCalls, incomingReactions);
    }

    /** Cập nhật tin đã gỡ trong transaction hiện tại, kể cả khi mới thấy qua lịch sử. */
    public void applyRecall(long messageId, long recalledAt) {
        if (messageId <= 0 || recalledAt <= 0) {
            throw new IllegalStateException("MESSAGE_RECALLED thiếu dữ liệu");
        }
        reactionEventVersion.incrementAndGet();
        MessageEntity existing = messageDao.findByServerId(messageId);
        messageDao.markRecalledByServerId(messageId, recalledAt);
        reactionDao.deleteByMessageServerId(messageId);
        if (existing != null) {
            translationDao.deleteAllForMessage(existing.getClientId());
        }
    }

    /** Giữ dấu xóa để bỏ phản hồi gửi tin hoặc trang lịch sử đến muộn. */
    public void applyDeleteForMe(@NonNull String conversationId, long messageId, @NonNull String myUserId) {
        if (conversationId == null || messageId <= 0) {
            throw new IllegalStateException("MESSAGE_DELETED_FOR_ME thiếu dữ liệu");
        }
        reactionEventVersion.incrementAndGet();
        MessageEntity existing = messageDao.findByServerId(messageId);
        messageDao.recordDeletionForMe(messageId);
        messageDao.deleteByServerId(messageId);
        reactionDao.deleteByMessageServerId(messageId);
        if (existing != null) {
            translationDao.deleteAllForMessage(existing.getClientId());
        }

        ConversationEntity conversation = conversationDao.getConversationSync(conversationId);
        if (conversation == null) return;
        if (Long.valueOf(messageId).equals(conversation.getLastMessageId())) {
            MessageEntity previous = messageDao.findLatestVisibleMessageSync(conversationId, myUserId);
            conversationDao.updateLastMessageMeta(conversationId,
                    previous == null ? null : previous.getServerId(),
                    previous == null ? 0L : previous.getCreatedAt());
        }
        // Chỉ trừ số chưa đọc nếu bản sao cục bộ đang giữ tin và tin nằm sau mốc đọc.
        if (existing != null && !myUserId.equals(existing.getSenderId()) && conversation.getUnreadCount() > 0) {
            ConversationMemberEntity self = conversationDao.getMemberSync(conversationId, myUserId);
            if (self == null || messageId > self.getLastReadId()) {
                conversationDao.setUnreadCount(conversationId, conversation.getUnreadCount() - 1);
            }
        }
    }

    /** Lưu danh sách cảm xúc đầy đủ từ API lịch sử hoặc phản hồi của chính thao tác. */
    public void syncMessageReactions(long messageId, @Nullable List<ChatDtos.ReactionDto> reactions) {
        if (messageDao.isDeletedForMe(messageId)) return;
        MessageEntity message = messageDao.findByServerId(messageId);
        if (message != null && message.getRecalledAt() != null) return;
        List<MessageReactionEntity> mapped = new ArrayList<>();
        if (reactions != null) {
            for (ChatDtos.ReactionDto reaction : reactions) {
                if (reaction != null && reaction.userId != null && reaction.emoji != null) {
                    mapped.add(new MessageReactionEntity(messageId, reaction.userId, reaction.emoji,
                            parseIsoMillis(reaction.createdAt, 0L)));
                }
            }
        }
        reactionDao.replaceForMessage(messageId, mapped);
    }

    @Nullable
    private NotificationHelper.ReactionNotice applyReactionEvent(
            @NonNull String myUserId,
            @NonNull ChatDtos.MessageReactionUpdatedPayload reaction) {
        if (reaction.messageId <= 0 || reaction.userId == null) {
            throw new IllegalStateException("MESSAGE_REACTION_UPDATED thiếu dữ liệu");
        }
        reactionEventVersion.incrementAndGet();
        if (messageDao.isDeletedForMe(reaction.messageId)) return null;
        MessageEntity message = messageDao.findByServerId(reaction.messageId);
        if (message != null && message.getRecalledAt() != null) return null;
        if (reaction.emoji == null || reaction.emoji.isEmpty()) {
            reactionDao.delete(reaction.messageId, reaction.userId);
            return null;
        }
        long timestampMs = parseIsoMillis(reaction.createdAt, 0L);
        reactionDao.upsert(new MessageReactionEntity(
                reaction.messageId, reaction.userId, reaction.emoji, timestampMs));
        if (myUserId.equals(reaction.userId)) return null;
        String convId = reaction.conversationId != null
                ? reaction.conversationId
                : (message != null ? message.getConversationId() : null);
        if (convId == null || convId.trim().isEmpty()) return null;
        return new NotificationHelper.ReactionNotice(
                convId, reaction.messageId, reaction.userId, reaction.emoji, timestampMs);
    }

    private void applyMessageCreated(
            String myUserId,
            ChatDtos.SyncEventDto event,
            Map<String, Long> highestIncoming,
            List<MessageEntity> newlyInsertedMessages,
            Map<String, CallOrigin> callOrigins) {
        ChatDtos.MessageDto dto = parseRequired(event.payload, ChatDtos.MessageDto.class, event.eventType);
        if (dto.conversationId == null || dto.senderId == null || dto.id <= 0) {
            throw new IllegalStateException("MESSAGE_CREATED thiếu trường bắt buộc");
        }
        MessageEntity existing = messageDao.findByServerId(dto.id);
        if (existing == null && dto.clientId != null) {
            existing = messageDao.findByClientId(dto.clientId);
        }

        List<ConversationMemberEntity> members = conversationDao.getMembersSync(dto.conversationId);
        String status = deriveMessageStatus(dto, myUserId, members);
        MessageEntity entity = toMessageEntity(dto, status);
        boolean inserted = messageDao.insertRespectingVisibleFrom(entity, myUserId);
        if (!inserted) {
            return;
        }
        // Payload phát lại có thể đã được server rút gọn hoặc gỡ nội dung từ trước.
        if (entity.getRecalledAt() != null) {
            applyRecall(dto.id, entity.getRecalledAt());
        } else if (dto.reactions != null) {
            syncMessageReactions(dto.id, dto.reactions);
        }

        if ("CALL".equals(entity.getType()) && dto.call != null && dto.call.id != null) {
            callOrigins.putIfAbsent(dto.call.id, new CallOrigin(dto.conversationId, dto.senderId, null));
        }

        conversationDao.updateLastMessageIfNewer(dto.conversationId, dto.id, entity.getCreatedAt());

        // Chỉ tăng unreadCount và đưa vào hàng đợi thông báo khi đây là tin mới từ người khác vượt quá mốc đã đọc hiện tại.
        if (existing == null && !myUserId.equals(dto.senderId)) {
            ConversationMemberEntity myMember = conversationDao.getMemberSync(dto.conversationId, myUserId);
            long myLastRead = myMember != null ? myMember.getLastReadId() : 0L;
            if (dto.id > myLastRead) {
                conversationDao.incrementUnread(dto.conversationId);
                if (entity.getRecalledAt() == null) newlyInsertedMessages.add(entity);
            }
            Long prev = highestIncoming.get(dto.conversationId);
            if (prev == null || dto.id > prev) {
                highestIncoming.put(dto.conversationId, dto.id);
            }
        }
    }

    /** Áp dụng trực tiếp ConversationDto trả về từ REST (tạo phòng 1-1, tạo/sửa nhóm) trong cùng transaction Room. */
    public void applyConversationDto(
            @NonNull String myUserId,
            @NonNull ChatDtos.ConversationDto dto,
            long nowMs) {
        if (dto.id == null || dto.type == null) return;
        upsertConversationAndMembers(myUserId, dto, nowMs, false);
    }

    private void applyConversationUpdated(String myUserId, ChatDtos.SyncEventDto event) {
        ChatDtos.ConversationDto dto = parseRequired(event.payload, ChatDtos.ConversationDto.class, event.eventType);
        if (dto.id == null || dto.type == null) {
            throw new IllegalStateException("CONVERSATION_UPDATED thiếu id hoặc type");
        }
        upsertConversationAndMembers(myUserId, dto, parseIsoMillis(event.createdAt, System.currentTimeMillis()), false);
    }

    private void upsertConversationAndMembers(
            String myUserId,
            ChatDtos.ConversationDto dto,
            long fallbackNowMs,
            boolean fromBootstrap) {
        ConversationEntity existingConv = conversationDao.getConversationSync(dto.id);
        ConversationMemberEntity oldMyMember = conversationDao.getMemberSync(dto.id, myUserId);
        long oldVisible = (oldMyMember != null && oldMyMember.getVisibleFromMessageId() != null)
                ? oldMyMember.getVisibleFromMessageId()
                : 0L;

        ConversationEntity conv = new ConversationEntity();
        conv.setId(dto.id);
        conv.setType(dto.type);
        conv.setTitle(dto.title);
        conv.setAvatarUrl(dto.avatarUrl);
        Long lastMessageId = dto.lastMessageId;
        // Snapshot cũ có thể trỏ đến một tin đã xóa phía thiết bị trong khi chờ HTTP.
        if (lastMessageId != null && messageDao.isDeletedForMe(lastMessageId)) {
            lastMessageId = existingConv == null ? null : existingConv.getLastMessageId();
        }
        conv.setLastMessageId(lastMessageId);
        long lastAt = dto.lastMessage != null
                ? parseIsoMillis(dto.lastMessage.createdAt, fallbackNowMs)
                : parseIsoMillis(dto.createdAt, existingConv != null ? existingConv.getLastMessageAt() : fallbackNowMs);
        if (existingConv != null && existingConv.getLastMessageAt() > lastAt && !fromBootstrap) {
            lastAt = existingConv.getLastMessageAt();
        }
        conv.setLastMessageAt(lastAt);
        // Với bootstrap hoặc refresh chính thức, unreadCount từ server là nguồn thẩm quyền; với event giữ giá trị hợp lý.
        conv.setUnreadCount(fromBootstrap ? dto.unreadCount : (existingConv != null ? existingConv.getUnreadCount() : dto.unreadCount));
        conv.setMuted(dto.muted);
        conversationDao.insert(conv);

        List<ConversationMemberEntity> upsertedMembers = new ArrayList<>();
        boolean myMemberSeen = false;
        long effectiveNewVisible = Math.max(oldVisible, dto.visibleFromMessageId);

        if (dto.members != null) {
            for (ChatDtos.MemberDto m : dto.members) {
                if (m == null || m.userId == null) continue;
                userDao.upsertPreservingPrivacy(toUserEntity(m.userId, null, m.displayName, m.avatarUrl));

                ConversationMemberEntity memberEntity = new ConversationMemberEntity();
                memberEntity.setConversationId(dto.id);
                memberEntity.setUserId(m.userId);
                memberEntity.setRole(m.role != null ? m.role : "MEMBER");
                memberEntity.setLastDeliveredId(m.lastDeliveredMessageId);
                memberEntity.setLastReadId(m.lastReadMessageId);
                boolean isMe = myUserId.equals(m.userId);
                boolean isLeft = m.leftAt != null || (isMe && dto.leftAt != null);
                memberEntity.setLeft(isLeft);
                long memberVisible = isMe ? Math.max(m.visibleFromMessageId, dto.visibleFromMessageId) : m.visibleFromMessageId;
                memberEntity.setVisibleFromMessageId(memberVisible > 0 ? memberVisible : null);
                conversationDao.upsertMemberMonotonic(memberEntity);

                ConversationMemberEntity persisted = conversationDao.getMemberSync(dto.id, m.userId);
                upsertedMembers.add(persisted != null ? persisted : memberEntity);
                if (isMe) {
                    myMemberSeen = true;
                    if (memberVisible > effectiveNewVisible) {
                        effectiveNewVisible = memberVisible;
                    }
                }
            }
        }

        // Trường hợp sự kiện CONVERSATION_UPDATED cuối khi rời nhóm không đính kèm đủ mảng members của bản thân.
        if (!myMemberSeen) {
            ConversationMemberEntity self = new ConversationMemberEntity();
            self.setConversationId(dto.id);
            self.setUserId(myUserId);
            self.setRole(oldMyMember != null ? oldMyMember.getRole() : "MEMBER");
            self.setLastDeliveredId(oldMyMember != null ? oldMyMember.getLastDeliveredId() : 0L);
            self.setLastReadId(oldMyMember != null ? oldMyMember.getLastReadId() : 0L);
            self.setLeft(dto.leftAt != null);
            self.setVisibleFromMessageId(effectiveNewVisible > 0 ? effectiveNewVisible : null);
            conversationDao.upsertMemberMonotonic(self);
            upsertedMembers.add(self);
        }

        // Khi mốc visibleFromMessageId tăng (vừa được thêm lại nhóm), dọn sạch tin server cũ <= mốc trong cùng transaction.
        // Tuyệt đối không xóa tin cục bộ (serverId IS NULL) hay sửa queuedVisibleFromMessageId của chúng (D-14).
        if (effectiveNewVisible > oldVisible && effectiveNewVisible > 0) {
            messageDao.purgeBeforeVisibleId(dto.id, effectiveNewVisible);
            conversationDao.recomputeLastMessageAfterPurge(dto.id, effectiveNewVisible);
        }

        if (dto.lastMessage != null) {
            List<ConversationMemberEntity> membersForStatus = !upsertedMembers.isEmpty()
                    ? upsertedMembers
                    : conversationDao.getMembersSync(dto.id);
            String derivedStatus = deriveMessageStatus(dto.lastMessage, myUserId, membersForStatus);
            MessageEntity latest = toMessageEntity(dto.lastMessage, derivedStatus);
            if (messageDao.insertRespectingVisibleFrom(latest, myUserId)) {
                if (latest.getRecalledAt() != null) {
                    applyRecall(dto.lastMessage.id, latest.getRecalledAt());
                } else if (dto.lastMessage.reactions != null) {
                    syncMessageReactions(dto.lastMessage.id, dto.lastMessage.reactions);
                }
            }
        }
    }

    private void applyReceiptUpdated(
            String myUserId, ChatDtos.SyncEventDto event, Map<String, Long> selfReadUpToInBatch) {
        ChatDtos.ReceiptUpdatePayload p = parseRequired(event.payload, ChatDtos.ReceiptUpdatePayload.class, event.eventType);
        if (p.conversationId == null || p.userId == null) {
            throw new IllegalStateException("RECEIPT_UPDATED thiếu conversationId hoặc userId");
        }

        ConversationMemberEntity oldMember = conversationDao.getMemberSync(p.conversationId, p.userId);
        ConversationMemberEntity updated = new ConversationMemberEntity();
        updated.setConversationId(p.conversationId);
        updated.setUserId(p.userId);
        updated.setRole(oldMember != null ? oldMember.getRole() : "MEMBER");
        updated.setLeft(oldMember != null && oldMember.getLeft());
        updated.setVisibleFromMessageId(oldMember != null ? oldMember.getVisibleFromMessageId() : null);
        updated.setLastDeliveredId(p.deliveredUpTo);
        updated.setLastReadId(p.readUpTo);
        conversationDao.upsertMemberMonotonic(updated);

        if (!myUserId.equals(p.userId)) {
            // Biên nhận từ thành viên khác -> nâng trạng thái các tin nhắn do chính mình gửi một cách đơn điệu.
            if (p.deliveredUpTo > 0) {
                messageDao.upgradeReceiptsForSender(p.conversationId, myUserId, p.deliveredUpTo, "DELIVERED");
            }
            if (p.readUpTo > 0) {
                messageDao.upgradeReceiptsForSender(p.conversationId, myUserId, p.readUpTo, "READ");
            }
        } else if (p.readUpTo > 0) {
            // Ghi nhận mốc tự đọc trong cùng lô để không phát thông báo cho tin nhắn đã đọc trên thiết bị khác.
            Long prevRead = selfReadUpToInBatch.get(p.conversationId);
            if (prevRead == null || p.readUpTo > prevRead) {
                selfReadUpToInBatch.put(p.conversationId, p.readUpTo);
            }
            // Biên nhận đọc từ chính tài khoản mình (trên thiết bị này hoặc thiết bị khác):
            // tính lại đúng số tin đến vượt trên max(lastReadId, visibleFromMessageId), không xóa trắng unread khi đọc một phần.
            ConversationMemberEntity persistedMe = conversationDao.getMemberSync(p.conversationId, myUserId);
            long effectiveRead = persistedMe != null
                    ? Math.max(persistedMe.getLastReadId(), p.readUpTo)
                    : Math.max(oldMember != null ? oldMember.getLastReadId() : 0L, p.readUpTo);
            long visible = (persistedMe != null && persistedMe.getVisibleFromMessageId() != null)
                    ? persistedMe.getVisibleFromMessageId()
                    : (oldMember != null && oldMember.getVisibleFromMessageId() != null ? oldMember.getVisibleFromMessageId() : 0L);
            long floor = Math.max(effectiveRead, visible);
            int remainingUnread = messageDao.countIncomingAfter(p.conversationId, myUserId, floor);
            conversationDao.setUnreadCount(p.conversationId, remainingUnread);
        }
    }

    private void applyDeviceRevoked(ChatDtos.SyncEventDto event) {
        // Sự kiện thu hồi thiết bị của thiết bị khác thuộc cùng tài khoản là no-op hợp lệ để con trỏ sync tiếp tục tiến.
        if (event.payload != null && event.payload.isJsonObject()) {
            ChatDtos.DeviceRevokedPayload payload = GSON.fromJson(event.payload, ChatDtos.DeviceRevokedPayload.class);
            if (payload != null
                    && currentDeviceIdHash != null
                    && currentDeviceIdHash.equals(payload.deviceIdHash)
                    && onSelfDeviceRevoked != null) {
                onSelfDeviceRevoked.run();
            }
        }
    }

    private void applyCallUpdated(
            String myUserId,
            ChatDtos.SyncEventDto event,
            List<NotificationHelper.MissedCallNotice> missedCalls,
            List<TerminalCallUpdate> terminalCalls,
            Map<String, CallOrigin> callOrigins,
            List<PendingMissedCall> pendingMissedCalls) {
        ChatDtos.CallUpdatePayload p = parseRequired(event.payload, ChatDtos.CallUpdatePayload.class, event.eventType);
        if (p.callId == null || p.callId.trim().isEmpty()) {
            throw new IllegalStateException("CALL_UPDATED thiếu callId");
        }

        // Đọc thêm conversationId, caller (CallerInfoDto), wsType từ payload Phase 6 mà vẫn tương thích Phase 5.
        JsonObject payloadObj = event.payload.getAsJsonObject();
        String conversationId = optString(payloadObj, "conversationId");
        String callerId = optString(payloadObj, "callerId");
        String callerDisplayName = optString(payloadObj, "callerDisplayName");
        if (payloadObj.has("caller") && payloadObj.get("caller").isJsonObject()) {
            JsonObject callerObj = payloadObj.getAsJsonObject("caller");
            String nestedId = optString(callerObj, "id");
            if (nestedId != null) callerId = nestedId;
            String nestedName = optString(callerObj, "displayName");
            if (nestedName != null) callerDisplayName = nestedName;
        }

        if (callerId != null) {
            callOrigins.put(p.callId, new CallOrigin(conversationId, callerId, callerDisplayName));
        }

        List<MessageEntity> callMessages = messageDao.findCallMessagesSync();
        if (callMessages != null && !callMessages.isEmpty()) {
            for (MessageEntity msg : callMessages) {
                if (msg == null || msg.getAttachmentJson() == null) continue;
                JsonElement parsed = JsonParser.parseString(msg.getAttachmentJson());
                if (!parsed.isJsonObject()) continue;
                JsonObject root = parsed.getAsJsonObject();
                if (!root.has("call") || !root.get("call").isJsonObject()) continue;
                JsonObject callObj = root.getAsJsonObject("call");
                JsonElement idEl = callObj.get("id");
                if (idEl == null || idEl.isJsonNull() || !p.callId.equals(idEl.getAsString())) {
                    continue;
                }
                if (callerId == null && msg.getSenderId() != null) {
                    callerId = msg.getSenderId();
                }
                if (conversationId == null && msg.getConversationId() != null) {
                    conversationId = msg.getConversationId();
                }
                // Chỉ cập nhật các trường được server gửi trong CALL_UPDATED, bảo toàn nguyên vẹn toàn bộ metadata còn lại.
                if (p.state != null) callObj.addProperty("state", p.state);
                if (p.endReason != null) callObj.addProperty("endReason", p.endReason);
                messageDao.updateAttachmentJson(msg.getLocalId(), GSON.toJson(root));
            }
        }

        if (p.state != null && ("ENDED".equals(p.state)
                || "MISSED".equals(p.state)
                || "REJECTED".equals(p.state)
                || "FAILED".equals(p.state)
                || "BUSY".equals(p.state)
                || "HANDLED_ELSEWHERE".equals(p.state))) {
            terminalCalls.add(new TerminalCallUpdate(p.callId, p.state, p.endReason));
        }

        if ("MISSED".equals(p.state)) {
            CallOrigin origin = callOrigins.get(p.callId);
            String effectiveCallerId = callerId != null ? callerId : (origin != null ? origin.callerId : null);
            String effectiveConvId = conversationId != null ? conversationId : (origin != null ? origin.conversationId : null);
            String effectiveName = callerDisplayName != null ? callerDisplayName : (origin != null ? origin.callerDisplayName : null);
            if (effectiveCallerId != null) {
                if (!myUserId.equals(effectiveCallerId)) {
                    missedCalls.add(new NotificationHelper.MissedCallNotice(
                            p.callId, effectiveConvId, effectiveCallerId, effectiveName));
                }
            } else {
                // Nếu MESSAGE_CREATED(type=CALL) nằm sau CALL_UPDATED trong cùng lô, đối chiếu lại sau khi quét hết lô.
                pendingMissedCalls.add(new PendingMissedCall(p.callId, effectiveConvId, effectiveName));
            }
        }
    }

    @Nullable
    private static String optString(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        String val = el.getAsString();
        return val != null && !val.trim().isEmpty() ? val.trim() : null;
    }

    private static final class CallOrigin {
        @Nullable final String conversationId;
        @Nullable final String callerId;
        @Nullable final String callerDisplayName;

        CallOrigin(@Nullable String conversationId, @Nullable String callerId, @Nullable String callerDisplayName) {
            this.conversationId = conversationId;
            this.callerId = callerId;
            this.callerDisplayName = callerDisplayName;
        }
    }

    private static final class PendingMissedCall {
        @NonNull final String callId;
        @Nullable final String conversationId;
        @Nullable final String callerDisplayName;

        PendingMissedCall(@NonNull String callId, @Nullable String conversationId, @Nullable String callerDisplayName) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.callerDisplayName = callerDisplayName;
        }
    }

    private void applyContactRequestEvent(ChatDtos.SyncEventDto event) {
        ChatDtos.BootstrapContactRequestDto req = parseRequired(
                event.payload, ChatDtos.BootstrapContactRequestDto.class, event.eventType);
        if (req.id == null || req.status == null) {
            throw new IllegalStateException(event.eventType + " thiếu id hoặc status");
        }
        if (req.requester != null && req.requester.id != null) {
            userDao.upsertPreservingPrivacy(
                    toUserEntity(req.requester.id, null, req.requester.displayName, req.requester.avatarUrl));
        }
        if (req.addressee != null && req.addressee.id != null) {
            userDao.upsertPreservingPrivacy(
                    toUserEntity(req.addressee.id, null, req.addressee.displayName, req.addressee.avatarUrl));
        }
        if ("PENDING".equals(req.status)) {
            if (req.requester == null || req.addressee == null) {
                throw new IllegalStateException("CONTACT_REQUEST PENDING thiếu requester hoặc addressee");
            }
            ContactRequestEntity entity = new ContactRequestEntity();
            entity.setId(req.id);
            entity.setRequesterId(req.requester.id);
            entity.setAddresseeId(req.addressee.id);
            entity.setStatus("PENDING");
            entity.setCreatedAt(parseIsoMillis(req.createdAt, System.currentTimeMillis()));
            contactDao.insertRequest(entity);
        } else {
            // ACCEPTED / REJECTED / CANCELED chỉ xóa lời mời đang chờ, không tự dựng bản ghi contact giả.
            contactDao.deleteRequest(req.id);
        }
    }

    private void applyContactUpdated(ChatDtos.SyncEventDto event) {
        ChatDtos.ContactUpdatePayload p = parseRequired(
                event.payload, ChatDtos.ContactUpdatePayload.class, event.eventType);
        if (p.contactUserId == null || p.contactUserId.trim().isEmpty()) {
            throw new IllegalStateException("CONTACT_UPDATED thiếu contactUserId");
        }
        if (p.removed || (!p.isFriend && !p.blocked)) {
            contactDao.deleteContact(p.contactUserId);
            return;
        }
        if (p.user != null && p.user.id != null) {
            userDao.upsertPreservingPrivacy(
                    toUserEntity(p.user.id, p.user.phone, p.user.displayName, p.user.avatarUrl));
        }
        ContactEntity contact = new ContactEntity();
        contact.setContactUserId(p.contactUserId);
        contact.setBlocked(p.blocked);
        contactDao.insert(contact);
    }

    private static UserEntity toUserEntity(
            String id,
            @Nullable String phone,
            @Nullable String displayName,
            @Nullable String avatarUrl) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPhone(phone);
        user.setDisplayName(displayName != null ? displayName : "");
        user.setAvatarUrl(avatarUrl);
        return user;
    }

    private static <T> T parseRequired(@Nullable JsonElement payload, Class<T> clazz, String eventType) {
        if (payload == null || !payload.isJsonObject()) {
            throw new IllegalStateException("Payload không hợp lệ cho sự kiện " + eventType);
        }
        T parsed = GSON.fromJson(payload, clazz);
        if (parsed == null) {
            throw new IllegalStateException("Không giải mã được payload cho sự kiện " + eventType);
        }
        return parsed;
    }

    public static long parseIsoMillis(@Nullable String iso, long fallbackMs) {
        if (iso == null || iso.trim().isEmpty()) return fallbackMs;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception ignored) {
            return fallbackMs;
        }
    }
}
