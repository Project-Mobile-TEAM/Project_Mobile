package com.utechat.data.repository.real;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.dao.ConversationDao;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.repository.ChatRepository;
import java.util.List;

/** Đọc offline qua Room; cơ chế gửi/sync/retry chỉ triển khai tại Phase5. */
public class RealChatRepository implements ChatRepository {
    private final ConversationDao conversations;
    private final MessageDao messages;
    private final ChatApi api; // Điểm nối REST của module chat.
    public RealChatRepository(ConversationDao conversations, MessageDao messages, ChatApi api) {
        this.conversations = conversations; this.messages = messages; this.api = api;
    }
    @Override public LiveData<List<ConversationEntity>> getConversations(String userId) {
        return conversations.getAllConversations();
    }
    @Override public LiveData<List<MessageEntity>> getMessages(String conversationId) {
        return messages.getMessagesForConversation(conversationId);
    }
    @Override public LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text) {
        // shortcut: cần WorkManager/idempotency ở Phase5; không lưu SENT khi chưa có server ACK.
        throw new UnsupportedOperationException("Gửi tin thật được triển khai ở Phase 5");
    }
}
