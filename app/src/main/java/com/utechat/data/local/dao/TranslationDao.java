package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** TranslationDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface TranslationDao {
    // Một tin có thể có nhiều bản dịch, mỗi targetLang là một dòng.
    @Upsert void insert(MessageTranslationEntity translation);
    @Query("SELECT * FROM message_translation WHERE messageKey = :key AND targetLang = :language")
    LiveData<MessageTranslationEntity> get(String key, String language);
}
