package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** ConversationDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface ConversationDao {
    // Sắp xếp theo thời gian để cả tin đang chờ serverId vẫn có thể lên đầu.
    @Upsert void insert(ConversationEntity conversation);
    @Upsert void insertMember(ConversationMemberEntity member);
    @Query("SELECT * FROM conversation ORDER BY lastMessageAt DESC") LiveData<List<ConversationEntity>> getAllConversations();
    @Query("SELECT * FROM member WHERE conversationId = :id") LiveData<List<ConversationMemberEntity>> getMembers(String id);
}
