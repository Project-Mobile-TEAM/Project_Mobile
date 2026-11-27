package com.utechat.data.repository;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.media.MediaPreparationHelper;
import com.utechat.data.sync.WsConnectionState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranh giới ChatRepository: tách hoàn toàn tầng giao diện (ViewModel/Fragment) khỏi Room, WorkManager, REST và WebSocket.
 * Giữ nguyên 3 phương thức nền tảng Phase 2 để tương thích ngược tuyệt đối với FakeRepositoriesTest.
 */
public interface ChatRepository {
    // ---- 3 phương thức Phase 2 (giữ nguyên chữ ký) ----
    LiveData<List<ConversationEntity>> getConversations(String userId);
    LiveData<List<MessageEntity>> getMessages(String conversationId);
    LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text);

    // ---- Quan sát dữ liệu thời gian thực & offline-first (Phase 5) ----
    LiveData<List<ConversationListItem>> observeConversationSummaries();
    LiveData<ConversationEntity> observeConversation(String conversationId);
    LiveData<List<ConversationMemberEntity>> observeMembers(String conversationId);
    LiveData<List<MessageEntity>> observeVisibleMessages(String conversationId, int limit);
    LiveData<UserEntity> observeUser(String userId);
    LiveData<Map<String, UserEntity>> observeAllUsersMap();
    LiveData<WsConnectionState> observeWsState();
    LiveData<Set<String>> observeTypingUsers(String conversationId);

    // ---- Thao tác hội thoại 1-1 & Nhóm ----
    LiveData<ChatResult<String>> openOrCreateDirect(String peerUserId);
    LiveData<ChatResult<String>> createGroup(String title, List<String> memberIds);
    LiveData<ChatResult<Boolean>> renameGroup(String conversationId, String title);
    LiveData<ChatResult<Boolean>> setMuted(String conversationId, boolean muted);
    LiveData<ChatResult<Boolean>> addMembers(String conversationId, List<String> userIds);
    LiveData<ChatResult<Boolean>> removeOrLeaveMember(String conversationId, String targetUserId, @Nullable String successorUserId);
    LiveData<ChatResult<Boolean>> updateMemberRole(String conversationId, String targetUserId, String role);

    // ---- Tải lịch sử, hàng đợi gửi offline, media & biên nhận ----
    LiveData<ChatResult<Integer>> loadOlderMessages(String conversationId, @Nullable Long beforeServerId, int limit);
    LiveData<Map<String, AttachmentTransferEntity>> observeTransfersMap();
    void enqueueTextMessage(String conversationId, String text);
    void enqueueMediaMessage(String conversationId, MediaPreparationHelper.PreparedMedia prepared, @Nullable String caption);
    void enqueueLocationMessage(String conversationId, double latitude, double longitude);
    // Thao tác Phase 7 trả lỗi mạng ngay, riêng gửi nhãn dán tiếp tục dùng hàng đợi offline.
    LiveData<List<MessageReactionEntity>> observeReactions(String conversationId);
    LiveData<ChatResult<Boolean>> recallMessage(String conversationId, long serverId);
    LiveData<ChatResult<Boolean>> deleteMessageForMe(String conversationId, long serverId);
    LiveData<ChatResult<Boolean>> toggleReaction(String conversationId, long serverId, @Nullable String emoji);
    void enqueueStickerMessage(String conversationId, StickerEntity sticker);
    void retryFailedMessage(String clientId);
    void deleteFailedMessage(String clientId);
    void markConversationRead(String conversationId, long upToServerId);
    void sendTypingStart(String conversationId);
    void sendTypingStop(String conversationId);

    // ---- Tìm kiếm FTS4 tiếng Việt ----
    LiveData<List<MessageEntity>> searchInConversation(String conversationId, String query);
    LiveData<List<MessageDao.MessageSearchHit>> searchGlobalMessages(String query, int limit);
    LiveData<Integer> computeWindowLimitForMessage(String conversationId, long targetLocalId, int minLimit);

    // ---- Điều khiển đồng bộ & kết nối ----
    void triggerSync();
    void connectRealtime();
    void disconnectRealtime();
    @Nullable String currentUserId();
}
