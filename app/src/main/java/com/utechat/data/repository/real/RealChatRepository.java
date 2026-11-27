package com.utechat.data.repository.real;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.work.WorkManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.utechat.data.MainThreadExecutor;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import com.utechat.data.local.entity.SyncStateEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.media.MediaPreparationHelper;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.ChatWebSocketManager;
import com.utechat.data.sync.SyncEngine;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.data.sync.TypingTracker;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.data.worker.SendMessageWorker;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Triển khai thật của ChatRepository kết hợp Room (Single Source of Truth), WorkManager (gửi offline),
 * Retrofit REST và WebSocket realtime. Mọi phản hồi lên UI đi qua mainExecutor tiêm được (không dùng ArchTaskExecutor).
 */
public class RealChatRepository implements ChatRepository {
    private static final Gson GSON = new Gson();
    private static final int SEARCH_CONTEXT_BUFFER = 10;

    private final AppDatabase db;
    private final ChatApi api;
    private final SessionManager session;
    private final SyncEngine syncEngine;
    private final ChatWebSocketManager wsManager;
    private final SyncEventApplier applier;
    private final TypingTracker typingTracker;
    @Nullable private final WorkManager workManager;
    private final Executor networkExecutor;
    private final Executor diskExecutor;
    private final Executor mainExecutor;

    public RealChatRepository(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @NonNull SessionManager session,
            @NonNull SyncEngine syncEngine,
            @NonNull ChatWebSocketManager wsManager,
            @NonNull SyncEventApplier applier,
            @NonNull TypingTracker typingTracker,
            @Nullable WorkManager workManager,
            @NonNull Executor networkExecutor,
            @NonNull Executor diskExecutor) {
        this(db, api, session, syncEngine, wsManager, applier, typingTracker, workManager,
                networkExecutor, diskExecutor, MainThreadExecutor.INSTANCE);
    }

    public RealChatRepository(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @NonNull SessionManager session,
            @NonNull SyncEngine syncEngine,
            @NonNull ChatWebSocketManager wsManager,
            @NonNull SyncEventApplier applier,
            @NonNull TypingTracker typingTracker,
            @Nullable WorkManager workManager,
            @NonNull Executor networkExecutor,
            @NonNull Executor diskExecutor,
            @NonNull Executor mainExecutor) {
        this.db = db;
        this.api = api;
        this.session = session;
        this.syncEngine = syncEngine;
        this.wsManager = wsManager;
        this.applier = applier;
        this.typingTracker = typingTracker;
        this.workManager = workManager;
        this.networkExecutor = networkExecutor;
        this.diskExecutor = diskExecutor;
        this.mainExecutor = Objects.requireNonNull(mainExecutor);
    }

    // ======================== PHASE 2 COMPATIBLE METHODS ========================

    @Override
    public LiveData<List<ConversationEntity>> getConversations(String userId) {
        return db.conversationDao().getAllConversations();
    }

    @Override
    public LiveData<List<MessageEntity>> getMessages(String conversationId) {
        return db.messageDao().getMessagesForConversation(conversationId);
    }

    @Override
    public LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text) {
        enqueueTextMessage(conversationId, text);
        return new MutableLiveData<>(null);
    }

    // ======================== REACTIVE ROOM & REALTIME OBSERVERS ========================

    @Override
    public LiveData<List<ConversationListItem>> observeConversationSummaries() {
        return Transformations.switchMap(activeSessionTrigger(), key -> {
            String meId = session.getUserId();
            if (key == null || meId == null) {
                return new MutableLiveData<>(Collections.emptyList());
            }
            return db.conversationDao().observeConversationSummaries(meId);
        });
    }

    @Override
    public LiveData<ConversationEntity> observeConversation(String conversationId) {
        return db.conversationDao().observeConversation(conversationId);
    }

    @Override
    public LiveData<List<ConversationMemberEntity>> observeMembers(String conversationId) {
        return db.conversationDao().getMembers(conversationId);
    }

    @Override
    public LiveData<List<MessageEntity>> observeVisibleMessages(String conversationId, int limit) {
        int safeLimit = Math.max(1, limit);
        return Transformations.switchMap(activeSessionTrigger(), key -> {
            String meId = session.getUserId();
            if (key == null || meId == null) {
                return new MutableLiveData<>(Collections.emptyList());
            }
            return db.messageDao().getVisibleMessagesWindow(conversationId, meId, safeLimit);
        });
    }

    @Override
    public LiveData<UserEntity> observeUser(String userId) {
        return db.userDao().getUserById(userId);
    }

    @Override
    public LiveData<Map<String, UserEntity>> observeAllUsersMap() {
        return Transformations.map(db.userDao().observeAllUsers(), users -> {
            Map<String, UserEntity> map = new LinkedHashMap<>();
            if (users != null) {
                for (UserEntity user : users) {
                    if (user != null && user.getId() != null) {
                        map.put(user.getId(), user);
                    }
                }
            }
            return Collections.unmodifiableMap(map);
        });
    }

    @Override
    public LiveData<WsConnectionState> observeWsState() {
        return wsManager.getConnectionState();
    }

    @Override
    public LiveData<Set<String>> observeTypingUsers(String conversationId) {
        return typingTracker.observeTypingUsers(conversationId);
    }

    // ======================== DIRECT & GROUP CONVERSATIONS ========================

    @Override
    public LiveData<ChatResult<String>> openOrCreateDirect(String peerUserId) {
        MutableLiveData<ChatResult<String>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }

        // Luôn xác thực quyền với server trước khi mở chat 1-1 (D-01); chỉ fallback cache khi mất mạng IOException.
        networkExecutor.execute(() -> {
            try {
                Response<ChatDtos.ConversationDto> res = api.openDirect(
                        new ChatDtos.DirectConversationRequest(peerUserId)).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful() && res.body() != null) {
                    ChatDtos.ConversationDto dto = res.body();
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> applier.applyConversationDto(meId, dto, System.currentTimeMillis())));
                        if (saved) {
                            postResult(out, ChatResult.ok(dto.id));
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException offline) {
                diskExecutor.execute(() -> {
                    final ConversationEntity[] cached = new ConversationEntity[1];
                    boolean active = session.runIfCurrent(sessionKey, () ->
                            cached[0] = db.conversationDao().findDirectConversationWithPeer(peerUserId));
                    if (!active) return;
                    if (cached[0] != null) {
                        postResult(out, ChatResult.ok(cached[0].getId()));
                    } else {
                        postResult(out, ChatResult.error("NETWORK"));
                    }
                });
            }
        });
        return out;
    }

    @Override
    public LiveData<ChatResult<String>> createGroup(String title, List<String> memberIds) {
        MutableLiveData<ChatResult<String>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        networkExecutor.execute(() -> {
            try {
                Response<ChatDtos.ConversationDto> res = api.createGroup(
                        new ChatDtos.CreateGroupRequest(title, memberIds)).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful() && res.body() != null) {
                    ChatDtos.ConversationDto dto = res.body();
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> applier.applyConversationDto(meId, dto, System.currentTimeMillis())));
                        if (saved) {
                            postResult(out, ChatResult.ok(dto.id));
                            syncEngine.requestSync();
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException e) {
                postResult(out, ChatResult.error("NETWORK"));
            }
        });
        return out;
    }

    @Override
    public LiveData<ChatResult<Boolean>> renameGroup(String conversationId, String title) {
        return runConversationUpdateMutation(conversationId, new ChatDtos.UpdateConversationRequest(title, null));
    }

    @Override
    public LiveData<ChatResult<Boolean>> setMuted(String conversationId, boolean muted) {
        return runConversationUpdateMutation(conversationId, new ChatDtos.UpdateConversationRequest(null, muted));
    }

    private LiveData<ChatResult<Boolean>> runConversationUpdateMutation(
            String conversationId, ChatDtos.UpdateConversationRequest request) {
        MutableLiveData<ChatResult<Boolean>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        networkExecutor.execute(() -> {
            try {
                Response<ChatDtos.ConversationDto> res = api.updateConversation(conversationId, request).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful() && res.body() != null) {
                    ChatDtos.ConversationDto dto = res.body();
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> {
                                    applier.applyConversationDto(meId, dto, System.currentTimeMillis());
                                    if (request.muted != null) {
                                        db.conversationDao().updateMuted(conversationId, request.muted);
                                    }
                                }));
                        if (saved) {
                            postResult(out, ChatResult.ok(true));
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException e) {
                postResult(out, ChatResult.error("NETWORK"));
            }
        });
        return out;
    }

    @Override
    public LiveData<ChatResult<Boolean>> addMembers(String conversationId, List<String> userIds) {
        MutableLiveData<ChatResult<Boolean>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        networkExecutor.execute(() -> {
            try {
                Response<ChatDtos.ConversationDto> res = api.addMembers(
                        conversationId, new ChatDtos.AddMembersRequest(userIds)).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful() && res.body() != null) {
                    ChatDtos.ConversationDto dto = res.body();
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> applier.applyConversationDto(meId, dto, System.currentTimeMillis())));
                        if (saved) {
                            postResult(out, ChatResult.ok(true));
                            syncEngine.requestSync();
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException e) {
                postResult(out, ChatResult.error("NETWORK"));
            }
        });
        return out;
    }

    @Override
    public LiveData<ChatResult<Boolean>> removeOrLeaveMember(
            String conversationId, String targetUserId, @Nullable String successorUserId) {
        MutableLiveData<ChatResult<Boolean>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        networkExecutor.execute(() -> {
            try {
                Response<Void> res = api.removeMember(conversationId, targetUserId, successorUserId).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful()) {
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> db.conversationDao().markMemberLeft(conversationId, targetUserId)));
                        if (saved) {
                            postResult(out, ChatResult.ok(true));
                            syncEngine.requestSync();
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException e) {
                postResult(out, ChatResult.error("NETWORK"));
            }
        });
        return out;
    }

    @Override
    public LiveData<ChatResult<Boolean>> updateMemberRole(String conversationId, String targetUserId, String role) {
        MutableLiveData<ChatResult<Boolean>> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        networkExecutor.execute(() -> {
            try {
                Response<ChatDtos.ConversationDto> res = api.updateMemberRole(
                        conversationId, targetUserId, new ChatDtos.UpdateRoleRequest(role)).execute();
                if (!sessionKey.equals(session.sessionKey())) return;
                if (res.isSuccessful() && res.body() != null) {
                    ChatDtos.ConversationDto dto = res.body();
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(sessionKey, () ->
                                db.runInTransaction(() -> applier.applyConversationDto(meId, dto, System.currentTimeMillis())));
                        if (saved) {
                            postResult(out, ChatResult.ok(true));
                        }
                    });
                } else {
                    postResult(out, parseErrorResult(res));
                }
            } catch (IOException e) {
                postResult(out, ChatResult.error("NETWORK"));
            }
        });
        return out;
    }

    // ======================== HISTORY PAGINATION & OFFLINE QUEUE ========================

    @Override
    public LiveData<ChatResult<Integer>> loadOlderMessages(
            String conversationId, @Nullable Long beforeServerId, int limit) {
        MutableLiveData<ChatResult<Integer>> out = new MutableLiveData<>();
        CapturedChatSession request = CapturedChatSession.capture(session);
        if (request == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        // Chụp con trỏ và mốc cảm xúc trước khi tải trang lịch sử để không bỏ sót reactions khi chỉ có biên nhận đọc mới.
        diskExecutor.execute(() -> session.runIfCurrent(request.key(), () -> {
            long cursor = readCursor(request.userId());
            long reactionVersion = applier.reactionEventVersion();
            networkExecutor.execute(() -> loadHistoryPage(
                    out, request, conversationId, beforeServerId, limit, cursor, reactionVersion));
        }));
        return out;
    }

    private void loadHistoryPage(MutableLiveData<ChatResult<Integer>> out, CapturedChatSession request,
                                 String conversationId, @Nullable Long beforeServerId, int limit,
                                 long cursor, long reactionVersion) {
        if (!request.key().equals(session.sessionKey())) {
            return;
        }
        try {
            Response<List<ChatDtos.MessageDto>> res =
                    api.getMessages(request.authorization(), conversationId, beforeServerId, limit).execute();
            if (!res.isSuccessful()) {
                ChatResult<Integer> error = parseErrorResult(res);
                if (res.code() == 401) {
                    request.expire(session);
                } else if (res.code() == 403 && "NOT_A_MEMBER".equals(error.errorCode)) {
                    diskExecutor.execute(() -> session.runIfCurrent(request.key(), () -> {
                        // Không ghi đè sự kiện tái gia nhập đã đến trong lúc trang lịch sử đang tải.
                        if (readCursor(request.userId()) == cursor) {
                            db.conversationDao().markMemberLeft(conversationId, request.userId());
                        }
                    }));
                }
                postForSession(out, request, error);
                return;
            }
            List<ChatDtos.MessageDto> list = res.body() != null ? res.body() : Collections.emptyList();
            diskExecutor.execute(() -> {
                boolean saved = session.runIfCurrent(request.key(), () -> db.runInTransaction(() -> {
                    String meId = request.userId();
                    boolean currentReactions = applier.reactionEventVersion() == reactionVersion;
                    List<ConversationMemberEntity> members = db.conversationDao().getMembersSync(conversationId);
                    for (ChatDtos.MessageDto dto : list) {
                        if (dto == null || dto.id <= 0 || !conversationId.equals(dto.conversationId)) {
                            continue;
                        }
                        // DAO bảo toàn mốc gỡ và kiểm dấu xóa riêng trước khi chèn trang lịch sử.
                        String status = SyncEventApplier.deriveMessageStatus(dto, meId, members);
                        MessageEntity message = SyncEventApplier.toMessageEntity(dto, status);
                        if (!db.messageDao().insertRespectingVisibleFrom(message, meId)) {
                            continue;
                        }
                        if (message.getRecalledAt() != null) {
                            applier.applyRecall(dto.id, message.getRecalledAt());
                        } else if (currentReactions
                                || (dto.reactions != null && !dto.reactions.isEmpty()
                                && db.reactionDao().findByMessageSync(dto.id).isEmpty())) {
                            applier.syncMessageReactions(dto.id, dto.reactions);
                        }
                    }
                }));
                if (saved) {
                    postForSession(out, request, ChatResult.ok(list.size()));
                }
            });
        } catch (IOException e) {
            postForSession(out, request, ChatResult.error("NETWORK"));
        }
    }

    @Override
    public LiveData<List<MessageReactionEntity>> observeReactions(String conversationId) {
        return db.reactionDao().observeByConversation(conversationId);
    }

    @Override
    public LiveData<ChatResult<Boolean>> recallMessage(String conversationId, long serverId) {
        return runMessageAction(
                auth -> api.recallMessage(auth, conversationId, serverId),
                (request, body) -> {
                    long at = body == null ? 0L : SyncEventApplier.parseIsoMillis(body.recalledAt, 0L);
                    if (at > 0L) {
                        applier.applyRecall(serverId, at);
                    }
                }, false);
    }

    @Override
    public LiveData<ChatResult<Boolean>> deleteMessageForMe(String conversationId, long serverId) {
        return runMessageAction(
                auth -> api.deleteMessageForMe(auth, conversationId, serverId),
                (request, body) -> applier.applyDeleteForMe(conversationId, serverId, request.userId()), false);
    }

    @Override
    public LiveData<ChatResult<Boolean>> toggleReaction(String conversationId, long serverId, @Nullable String emoji) {
        return runMessageAction(
                auth -> api.updateReaction(auth, conversationId, serverId, new ChatDtos.ReactionRequest(emoji)),
                (request, body) -> {
                    if (body != null && body.messageId == serverId) {
                        applier.syncMessageReactions(serverId, body.reactions);
                    }
                }, true);
    }

    /** Thao tác nhấn giữ chỉ sửa Room sau phản hồi thành công của đúng phiên. */
    private <T> LiveData<ChatResult<Boolean>> runMessageAction(
            Function<String, Call<T>> createCall, BiConsumer<CapturedChatSession, T> apply, boolean checkCursor) {
        MutableLiveData<ChatResult<Boolean>> out = new MutableLiveData<>();
        CapturedChatSession request = CapturedChatSession.capture(session);
        if (request == null) {
            postResult(out, ChatResult.error("UNAUTHENTICATED"));
            return out;
        }
        diskExecutor.execute(() -> session.runIfCurrent(request.key(), () -> {
            long cursor = checkCursor ? readCursor(request.userId()) : -1L;
            networkExecutor.execute(() -> {
                if (!request.key().equals(session.sessionKey())) {
                    return;
                }
                try {
                    Response<T> response = createCall.apply(request.authorization()).execute();
                    if (!response.isSuccessful()) {
                        if (response.code() == 401) {
                            request.expire(session);
                        }
                        postForSession(out, request, parseErrorResult(response));
                        return;
                    }
                    diskExecutor.execute(() -> {
                        boolean saved = session.runIfCurrent(request.key(), () -> db.runInTransaction(() -> {
                            if (!checkCursor || readCursor(request.userId()) == cursor) {
                                apply.accept(request, response.body());
                            }
                        }));
                        if (saved) {
                            syncEngine.requestSync();
                            postForSession(out, request, ChatResult.ok(true));
                        }
                    });
                } catch (IOException e) {
                    postForSession(out, request, ChatResult.error("NETWORK"));
                }
            });
        }));
        return out;
    }

    private long readCursor(String userId) {
        SyncStateEntity state = db.syncStateDao().get(userId);
        return state == null ? 0L : state.getLastSyncEventId();
    }

    private <T> void postForSession(MutableLiveData<ChatResult<T>> out, CapturedChatSession request, ChatResult<T> value) {
        mainExecutor.execute(() -> {
            if (request.key().equals(session.sessionKey())) {
                out.setValue(value);
            }
        });
    }

    @Override
    public LiveData<Map<String, AttachmentTransferEntity>> observeTransfersMap() {
        return Transformations.map(db.transferDao().observeAll(), list -> {
            Map<String, AttachmentTransferEntity> map = new LinkedHashMap<>();
            if (list != null) {
                for (AttachmentTransferEntity item : list) {
                    if (item != null && item.getClientId() != null) {
                        map.put(item.getClientId(), item);
                    }
                }
            }
            return Collections.unmodifiableMap(map);
        });
    }

    @Override
    public void enqueueTextMessage(String conversationId, String text) {
        if (conversationId == null || text == null || text.trim().isEmpty()) {
            return;
        }
        String trimmed = text.trim();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            return;
        }

        diskExecutor.execute(() -> {
            final String[] queuedClientId = new String[1];
            session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                ConversationEntity conv = db.conversationDao().getConversationSync(conversationId);
                Long queuedCutoff = null;
                if (conv != null && "GROUP".equals(conv.getType())) {
                    // Chỉ chụp mốc visibleFromMessageId khi biết chắc thành viên nhóm đang hoạt động; không đoán mò.
                    ConversationMemberEntity myMember = db.conversationDao().getMemberSync(conversationId, meId);
                    if (myMember == null || myMember.getLeft()) {
                        return;
                    }
                    queuedCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                }

                String clientId = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                MessageEntity msg = new MessageEntity();
                msg.setClientId(clientId);
                msg.setServerId(null);
                msg.setConversationId(conversationId);
                msg.setSenderId(meId);
                msg.setType("TEXT");
                msg.setText(trimmed);
                msg.setNormalizedText(MessageText.normalize(trimmed));
                msg.setCreatedAt(now);
                msg.setStatus("SENDING");
                msg.setErrorCode(null);
                msg.setQueuedVisibleFromMessageId(queuedCutoff);
                db.messageDao().insert(msg);

                if (conv != null && now >= conv.getLastMessageAt()) {
                    conv.setLastMessageAt(now);
                    db.conversationDao().insert(conv);
                }
                queuedClientId[0] = clientId;
            }));

            if (queuedClientId[0] != null && workManager != null && sessionKey.equals(session.sessionKey())) {
                SendMessageWorker.enqueue(workManager, queuedClientId[0], sessionKey);
            }
        });
    }

    @Override
    public void enqueueMediaMessage(
            String conversationId, MediaPreparationHelper.PreparedMedia prepared, @Nullable String caption) {
        if (conversationId == null || prepared == null) {
            return;
        }
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            deletePendingFileQuietly(prepared.localPath);
            return;
        }
        String cleanCaption = (caption != null && !caption.trim().isEmpty()) ? caption.trim() : null;
        String resolvedClientId = (prepared.clientId != null && !prepared.clientId.trim().isEmpty())
                ? prepared.clientId.trim()
                : UUID.randomUUID().toString();

        diskExecutor.execute(() -> {
            final String[] queuedClientId = new String[1];
            session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                ConversationEntity conv = db.conversationDao().getConversationSync(conversationId);
                Long queuedCutoff = null;
                if (conv != null && "GROUP".equals(conv.getType())) {
                    ConversationMemberEntity myMember = db.conversationDao().getMemberSync(conversationId, meId);
                    if (myMember == null || myMember.getLeft()) {
                        return;
                    }
                    queuedCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                }

                long now = System.currentTimeMillis();
                JsonObject meta = new JsonObject();
                meta.addProperty("localPath", prepared.localPath);
                meta.addProperty("fileName", prepared.fileName);
                meta.addProperty("mime", prepared.mime);
                meta.addProperty("sizeBytes", prepared.sizeBytes);
                if (prepared.durationMs != null) meta.addProperty("durationMs", prepared.durationMs);
                if (prepared.width != null) meta.addProperty("width", prepared.width);
                if (prepared.height != null) meta.addProperty("height", prepared.height);

                MessageEntity msg = new MessageEntity();
                msg.setClientId(resolvedClientId);
                msg.setServerId(null);
                msg.setConversationId(conversationId);
                msg.setSenderId(meId);
                msg.setType(prepared.kind);
                msg.setText(cleanCaption);
                msg.setNormalizedText(cleanCaption != null ? MessageText.normalize(cleanCaption) : null);
                msg.setAttachmentJson(meta.toString());
                msg.setCreatedAt(now);
                msg.setStatus("SENDING");
                msg.setErrorCode(null);
                msg.setQueuedVisibleFromMessageId(queuedCutoff);
                db.messageDao().insert(msg);

                // Ghi nguyên tử bản ghi AttachmentTransferEntity trạng thái QUEUED cùng với MessageEntity (D-04).
                AttachmentTransferEntity transfer = new AttachmentTransferEntity();
                transfer.setClientId(resolvedClientId);
                transfer.setDirection("UPLOAD");
                transfer.setLocalUri(prepared.localPath);
                transfer.setLocalPath(prepared.localPath);
                transfer.setState("QUEUED");
                transfer.setProgress(0);
                transfer.setKind(prepared.kind);
                transfer.setMime(prepared.mime);
                transfer.setSizeBytes(prepared.sizeBytes);
                transfer.setFileName(prepared.fileName);
                transfer.setDurationMs(prepared.durationMs);
                transfer.setWidth(prepared.width);
                transfer.setHeight(prepared.height);
                transfer.setErrorCode(null);
                transfer.setMediaUrl(null);
                transfer.setUpdatedAt(now);
                db.transferDao().insert(transfer);

                if (conv != null && now >= conv.getLastMessageAt()) {
                    conv.setLastMessageAt(now);
                    db.conversationDao().insert(conv);
                }
                queuedClientId[0] = resolvedClientId;
            }));

            if (queuedClientId[0] == null) {
                // Phiên đã đổi hoặc người dùng vừa rời nhóm trước khi ghi Room -> xóa tệp tạm tránh rò rỉ đĩa.
                deletePendingFileQuietly(prepared.localPath);
            } else if (workManager != null && sessionKey.equals(session.sessionKey())) {
                SendMessageWorker.enqueue(workManager, queuedClientId[0], sessionKey);
            }
        });
    }

    private static void deletePendingFileQuietly(@Nullable String localPath) {
        if (localPath == null || localPath.trim().isEmpty()) {
            return;
        }
        File file = new File(localPath);
        if (file.isFile() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    @Override
    public void enqueueLocationMessage(String conversationId, double latitude, double longitude) {
        if (conversationId == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return;
        }
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            return;
        }

        diskExecutor.execute(() -> {
            final String[] queuedClientId = new String[1];
            session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                ConversationEntity conv = db.conversationDao().getConversationSync(conversationId);
                Long queuedCutoff = null;
                if (conv != null && "GROUP".equals(conv.getType())) {
                    ConversationMemberEntity myMember = db.conversationDao().getMemberSync(conversationId, meId);
                    if (myMember == null || myMember.getLeft()) {
                        return;
                    }
                    queuedCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                }

                String clientId = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                JsonObject meta = new JsonObject();
                meta.addProperty("latitude", latitude);
                meta.addProperty("longitude", longitude);

                MessageEntity msg = new MessageEntity();
                msg.setClientId(clientId);
                msg.setServerId(null);
                msg.setConversationId(conversationId);
                msg.setSenderId(meId);
                msg.setType("LOCATION");
                msg.setText(null);
                msg.setNormalizedText(null);
                msg.setAttachmentJson(meta.toString());
                msg.setCreatedAt(now);
                msg.setStatus("SENDING");
                msg.setErrorCode(null);
                msg.setQueuedVisibleFromMessageId(queuedCutoff);
                db.messageDao().insert(msg);

                if (conv != null && now >= conv.getLastMessageAt()) {
                    conv.setLastMessageAt(now);
                    db.conversationDao().insert(conv);
                }
                queuedClientId[0] = clientId;
            }));

            if (queuedClientId[0] != null && workManager != null && sessionKey.equals(session.sessionKey())) {
                SendMessageWorker.enqueue(workManager, queuedClientId[0], sessionKey);
            }
        });
    }

    /** Lưu nhãn dán vào cùng hàng đợi với tin chữ, để WorkManager gửi bằng clientId đã tạo. */
    @Override
    public void enqueueStickerMessage(String conversationId, StickerEntity sticker) {
        if (conversationId == null || sticker == null || sticker.getStickerId().isBlank()) {
            return;
        }
        CapturedChatSession request = CapturedChatSession.capture(session);
        if (request == null) {
            return;
        }
        String stickerId = sticker.getStickerId();
        diskExecutor.execute(() -> session.runIfCurrent(request.key(), () -> {
            String clientId = db.runInTransaction(() -> storePendingSticker(conversationId, stickerId, request.userId()));
            if (clientId != null) {
                SendMessageWorker.enqueue(workManager, clientId, request.key());
            }
        }));
    }

    private String storePendingSticker(String conversationId, String stickerId, String meId) {
        // Lấy metadata từ kho của phiên hiện tại, rồi kiểm mốc thành viên trước khi xếp hàng.
        StickerEntity sticker = db.stickerDao().findStickerByIdSync(stickerId);
        ConversationEntity conversation = db.conversationDao().getConversationSync(conversationId);
        ConversationMemberEntity member = db.conversationDao().getMemberSync(conversationId, meId);
        if (sticker == null || conversation == null || member == null || member.getLeft()) {
            return null;
        }
        StickerPackEntity pack = db.stickerDao().findPackByIdSync(sticker.getPackId());
        if (pack == null || !pack.getInstalled()) {
            return null;
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("stickerId", sticker.getStickerId());
        metadata.addProperty("packId", sticker.getPackId());
        metadata.addProperty("code", sticker.getCode());
        metadata.addProperty("label", sticker.getLabel());
        metadata.addProperty("imageUrl", sticker.getImageUrl());
        metadata.addProperty("localPath", sticker.getLocalPath());
        metadata.addProperty("width", sticker.getWidth());
        metadata.addProperty("height", sticker.getHeight());

        String clientId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        MessageEntity message = new MessageEntity();
        message.setClientId(clientId);
        message.setConversationId(conversationId);
        message.setSenderId(meId);
        message.setType("STICKER");
        message.setAttachmentJson(metadata.toString());
        message.setCreatedAt(now);
        message.setStatus("SENDING");
        if ("GROUP".equals(conversation.getType())) {
            Long cutoff = member.getVisibleFromMessageId();
            message.setQueuedVisibleFromMessageId(cutoff == null ? 0L : cutoff);
        }
        db.messageDao().insert(message);
        if (now >= conversation.getLastMessageAt()) {
            conversation.setLastMessageAt(now);
            db.conversationDao().insert(conversation);
        }
        return clientId;
    }

    @Override
    public void retryFailedMessage(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) return;
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) return;

        // Thao tác "Gửi lại" chủ động của người dùng: giữ nguyên clientId, và với GROUP lấy mốc hiện tại mới nhất.
        diskExecutor.execute(() -> {
            final boolean[] readyToEnqueue = new boolean[1];
            session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                MessageEntity msg = db.messageDao().findByClientId(clientId);
                if (msg == null
                        || msg.getServerId() != null
                        || !"FAILED".equals(msg.getStatus())
                        || !meId.equals(msg.getSenderId())) {
                    return;
                }
                ConversationEntity conv = db.conversationDao().getConversationSync(msg.getConversationId());
                Long newCutoff = null;
                if (conv != null && "GROUP".equals(conv.getType())) {
                    ConversationMemberEntity myMember = db.conversationDao().getMemberSync(msg.getConversationId(), meId);
                    if (myMember == null || myMember.getLeft()) {
                        return;
                    }
                    newCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                }
                int updated = db.messageDao().resetForRetry(clientId, newCutoff);
                if (updated > 0) {
                    // Đặt lại trạng thái transfer về UPLOADED (nếu đã có mediaUrl) hoặc QUEUED trước khi xếp lại hàng đợi (D-04).
                    var transferDao = db.transferDao();
                    if (transferDao != null) {
                        AttachmentTransferEntity transfer = transferDao.findByClientIdSync(clientId);
                        if (transfer != null) {
                            boolean uploaded = transfer.getMediaUrl() != null && !transfer.getMediaUrl().trim().isEmpty();
                            transferDao.updateState(
                                    clientId,
                                    uploaded ? "UPLOADED" : "QUEUED",
                                    uploaded ? 100 : 0,
                                    null,
                                    System.currentTimeMillis());
                        }
                    }
                    readyToEnqueue[0] = true;
                }
            }));

            if (readyToEnqueue[0] && workManager != null && sessionKey.equals(session.sessionKey())) {
                SendMessageWorker.enqueue(workManager, clientId, sessionKey);
            }
        });
    }

    @Override
    public void deleteFailedMessage(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) return;
        String sessionKey = session.sessionKey();
        if (sessionKey == null) return;

        // Chỉ xóa bản ghi FAILED cục bộ (serverId IS NULL) kèm tệp tạm pending_media và dòng transfer; không hủy chuỗi gửi chung.
        diskExecutor.execute(() -> {
            final String[] localPathToDelete = new String[1];
            session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                var transferDao = db.transferDao();
                AttachmentTransferEntity transfer = transferDao != null ? transferDao.findByClientIdSync(clientId) : null;
                int deleted = db.messageDao().deleteFailedLocal(clientId);
                if (deleted > 0 && transfer != null) {
                    localPathToDelete[0] = transfer.getLocalPath();
                    transferDao.deleteByClientId(clientId);
                }
            }));
            if (localPathToDelete[0] != null && !localPathToDelete[0].trim().isEmpty()) {
                File f = new File(localPathToDelete[0]);
                if (f.isFile() && !f.delete()) {
                    f.deleteOnExit();
                }
            }
        });
    }

    @Override
    public void markConversationRead(String conversationId, long upToServerId) {
        if (conversationId == null || upToServerId <= 0) return;
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) return;

        diskExecutor.execute(() -> {
            final boolean[] shouldPost = new boolean[1];
            session.runIfCurrent(sessionKey, () -> {
                ConversationMemberEntity myMember = db.conversationDao().getMemberSync(conversationId, meId);
                long visible = (myMember != null && myMember.getVisibleFromMessageId() != null)
                        ? myMember.getVisibleFromMessageId() : 0L;
                long currentRead = myMember != null ? myMember.getLastReadId() : 0L;
                if (upToServerId > visible && upToServerId > currentRead) {
                    shouldPost[0] = true;
                }
            });
            if (!shouldPost[0]) return;

            networkExecutor.execute(() -> {
                try {
                    Response<Void> res = api.markRead(conversationId, new ChatDtos.WatermarkRequest(upToServerId)).execute();
                    if (!sessionKey.equals(session.sessionKey()) || !res.isSuccessful()) return;
                    diskExecutor.execute(() -> session.runIfCurrent(sessionKey, () -> db.runInTransaction(() -> {
                        ConversationMemberEntity old = db.conversationDao().getMemberSync(conversationId, meId);
                        ConversationMemberEntity updated = new ConversationMemberEntity();
                        updated.setConversationId(conversationId);
                        updated.setUserId(meId);
                        updated.setRole(old != null ? old.getRole() : "MEMBER");
                        updated.setLeft(old != null && old.getLeft());
                        updated.setVisibleFromMessageId(old != null ? old.getVisibleFromMessageId() : null);
                        updated.setLastDeliveredId(Math.max(old != null ? old.getLastDeliveredId() : 0L, upToServerId));
                        updated.setLastReadId(Math.max(old != null ? old.getLastReadId() : 0L, upToServerId));
                        db.conversationDao().upsertMemberMonotonic(updated);

                        long visible = updated.getVisibleFromMessageId() != null ? updated.getVisibleFromMessageId() : 0L;
                        long floor = Math.max(updated.getLastReadId(), visible);
                        // Tính lại đúng số tin chưa đọc vượt sau mốc vừa xác nhận, không xóa mù quáng về 0.
                        int remainingUnread = db.messageDao().countIncomingAfter(conversationId, meId, floor);
                        db.conversationDao().setUnreadCount(conversationId, remainingUnread);
                    })));
                } catch (IOException ignored) { }
            });
        });
    }

    @Override
    public void sendTypingStart(String conversationId) {
        wsManager.sendTypingStart(conversationId);
    }

    @Override
    public void sendTypingStop(String conversationId) {
        wsManager.sendTypingStop(conversationId);
    }

    // ======================== VIETNAMESE FTS4 SEARCH ========================

    @Override
    public LiveData<List<MessageEntity>> searchInConversation(String conversationId, String query) {
        MutableLiveData<List<MessageEntity>> out = new MutableLiveData<>();
        String safeQuery = MessageText.safeMatchQuery(query);
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (safeQuery == null || sessionKey == null || meId == null) {
            postValue(out, Collections.emptyList());
            return out;
        }
        diskExecutor.execute(() -> {
            final List<MessageEntity>[] results = new List[1];
            boolean active = session.runIfCurrent(sessionKey, () ->
                    results[0] = db.messageDao().searchInConversationVisible(conversationId, meId, safeQuery));
            if (active) {
                postValue(out, results[0] != null ? results[0] : Collections.emptyList());
            }
        });
        return out;
    }

    @Override
    public LiveData<List<MessageDao.MessageSearchHit>> searchGlobalMessages(String query, int limit) {
        MutableLiveData<List<MessageDao.MessageSearchHit>> out = new MutableLiveData<>();
        String safeQuery = MessageText.safeMatchQuery(query);
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (safeQuery == null || sessionKey == null || meId == null) {
            postValue(out, Collections.emptyList());
            return out;
        }
        int safeLimit = Math.max(1, limit);
        diskExecutor.execute(() -> {
            final List<MessageDao.MessageSearchHit>[] results = new List[1];
            boolean active = session.runIfCurrent(sessionKey, () ->
                    results[0] = db.messageDao().searchGlobalVisible(meId, safeQuery, safeLimit, 0));
            if (active) {
                postValue(out, results[0] != null ? results[0] : Collections.emptyList());
            }
        });
        return out;
    }

    @Override
    public LiveData<Integer> computeWindowLimitForMessage(String conversationId, long targetLocalId, int minLimit) {
        MutableLiveData<Integer> out = new MutableLiveData<>();
        String sessionKey = session.sessionKey();
        String meId = session.getUserId();
        if (sessionKey == null || meId == null) {
            postValue(out, minLimit);
            return out;
        }
        diskExecutor.execute(() -> {
            final int[] computed = {minLimit};
            boolean active = session.runIfCurrent(sessionKey, () -> {
                int newerCount = db.messageDao().countVisibleMessagesNewerThan(conversationId, meId, targetLocalId);
                computed[0] = Math.max(minLimit, newerCount + SEARCH_CONTEXT_BUFFER);
            });
            if (active) {
                postValue(out, computed[0]);
            }
        });
        return out;
    }

    // ======================== SYNC & REALTIME LIFECYCLE ========================

    @Override
    public void triggerSync() {
        syncEngine.requestSync();
    }

    @Override
    public void connectRealtime() {
        wsManager.connectIfAuthenticated();
    }

    @Override
    public void disconnectRealtime() {
        wsManager.disconnect();
    }

    @Override
    @Nullable
    public String currentUserId() {
        return session.getUserId();
    }

    // ======================== INTERNAL HELPERS ========================

    /** Phát ngay sessionKey hiện tại và tiếp tục theo dõi sessionChanges() để các truy vấn Room tự đổi theo phiên. */
    private LiveData<String> activeSessionTrigger() {
        MediatorLiveData<String> trigger = new MediatorLiveData<>();
        trigger.setValue(session.sessionKey());
        trigger.addSource(session.sessionChanges(), value -> {
            if (!Objects.equals(trigger.getValue(), value)) {
                trigger.setValue(value);
            }
        });
        return trigger;
    }

    private <T> void postResult(MutableLiveData<ChatResult<T>> target, ChatResult<T> value) {
        mainExecutor.execute(() -> target.setValue(value));
    }

    private <T> void postValue(MutableLiveData<T> target, T value) {
        mainExecutor.execute(() -> target.setValue(value));
    }

    static <T> ChatResult<T> parseErrorResult(Response<?> response) {
        String code = "HTTP_" + response.code();
        Long retryAfter = null;
        try {
            if (response.errorBody() != null) {
                ChatDtos.ErrorBody body = GSON.fromJson(response.errorBody().string(), ChatDtos.ErrorBody.class);
                if (body != null && body.error != null) {
                    if (body.error.code != null && !body.error.code.trim().isEmpty()) {
                        code = body.error.code;
                    }
                    if (body.error.details != null && body.error.details.retryAfterSeconds != null) {
                        retryAfter = body.error.details.retryAfterSeconds;
                    }
                }
            }
        } catch (Exception ignored) { }
        return ChatResult.error(code, retryAfter);
    }
}
