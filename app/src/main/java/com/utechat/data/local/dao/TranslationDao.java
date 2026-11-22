package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;
import com.utechat.data.local.entity.MessageTranslationEntity;
import java.util.List;

/** TranslationDao: giao tiếp SQLite cho cache bản dịch cục bộ message_translation (D-14). */
@Dao
public interface TranslationDao {
    // Một tin có thể có nhiều bản dịch, mỗi targetLang là một dòng.
    @Upsert
    void insert(MessageTranslationEntity translation);

    @Query("SELECT * FROM message_translation WHERE messageKey = :key AND targetLang = :language")
    LiveData<MessageTranslationEntity> get(String key, String language);

    // Tra cứu cache dịch đồng bộ trên luồng nền trước khi gọi ML Kit Translate.
    @Query("SELECT * FROM message_translation WHERE messageKey = :key AND targetLang = :language LIMIT 1")
    MessageTranslationEntity findSync(String key, String language);

    // Quan sát toàn bộ bản dịch thuộc một hội thoại (nối qua message.clientId = message_translation.messageKey).
    @Query("SELECT mt.* FROM message_translation mt INNER JOIN message m ON m.clientId = mt.messageKey WHERE m.conversationId = :conversationId")
    LiveData<List<MessageTranslationEntity>> observeByConversation(String conversationId);

    // Xóa một bản dịch cụ thể khi người dùng muốn dịch lại hoặc ẩn bản dịch.
    @Query("DELETE FROM message_translation WHERE messageKey = :key AND targetLang = :language")
    void deleteByKey(String key, String language);

    // Dọn các bản dịch mồ côi khi tin nhắn gốc đã bị xóa hoặc cô lập lịch sử nhóm.
    @Query("DELETE FROM message_translation WHERE messageKey NOT IN (SELECT clientId FROM message)")
    void deleteOrphaned();
}
