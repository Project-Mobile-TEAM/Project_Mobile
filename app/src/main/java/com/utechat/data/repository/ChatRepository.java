package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.*;
import java.util.List;

/** Ranh giới ChatRepository: UI không phụ thuộc cách lưu SQLite hoặc gọi REST. */
public interface ChatRepository {
    // clientId và localId được quản lý bên trong repository, màn hình chỉ gửi ý định.
    LiveData<List<ConversationEntity>> getConversations(String userId);
    LiveData<List<MessageEntity>> getMessages(String conversationId);
    LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text);
}
