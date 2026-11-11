package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** MessageDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface MessageDao {
    // Mọi đường ghi tin đều chuẩn hóa text trong cùng transaction với bản gốc.
    @Transaction
    default void insert(MessageEntity message) {
        // Giữ localId cũ khi server xác nhận cùng clientId, không tạo dòng tin thứ hai.
        Long existing = findLocalId(message.getClientId());
        if (existing != null) message.setLocalId(existing);
        message.setNormalizedText(MessageText.normalize(message.getText()));
        upsert(message);
    }
    @Query("SELECT localId FROM message WHERE clientId = :clientId") Long findLocalId(String clientId);
    @Upsert void upsert(MessageEntity message);
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC, localId ASC")
    LiveData<List<MessageEntity>> getMessagesForConversation(String conversationId);
    // Chỉ gọi từ executor của repository; query là đầu ra MessageText.matchQuery().
    @Query("SELECT m.* FROM message m JOIN message_fts ON m.localId = message_fts.rowid WHERE message_fts MATCH :query AND m.conversationId = :conversationId ORDER BY m.createdAt DESC")
    List<MessageEntity> search(String conversationId, String query);
}
