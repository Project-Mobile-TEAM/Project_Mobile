package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;
import com.utechat.data.local.entity.MessageReactionEntity;
import java.util.List;

/** Lưu một cảm xúc trên mỗi cặp (messageServerId, userId). */
@Dao
public interface ReactionDao {
    // Thay thế cảm xúc cũ của cùng một người khi nhận sự kiện.
    @Upsert
    void upsert(MessageReactionEntity reaction);

    @Upsert
    void upsertAll(List<MessageReactionEntity> reactions);

    // Bỏ cảm xúc của một người và giữ lựa chọn của người khác.
    @Query("DELETE FROM message_reaction WHERE messageServerId = :messageServerId AND userId = :userId")
    void delete(long messageServerId, String userId);

    // Xóa toàn bộ cảm xúc khi tin bị gỡ hoặc ẩn.
    @Query("DELETE FROM message_reaction WHERE messageServerId = :messageServerId")
    void deleteByMessageServerId(long messageServerId);

    // Áp dụng ảnh chụp lịch sử nguyên tử để tránh hiển thị danh sách rỗng tạm thời.
    @Transaction
    default void replaceForMessage(long messageServerId, List<MessageReactionEntity> reactions) {
        deleteByMessageServerId(messageServerId);
        if (!reactions.isEmpty()) {
            upsertAll(reactions);
        }
    }

    // Chỉ hiển thị cảm xúc của tin còn trong hội thoại và chưa bị gỡ.
    @Query("SELECT r.* FROM message_reaction r INNER JOIN message m ON m.serverId = r.messageServerId "
            + "WHERE m.conversationId = :conversationId AND m.recalledAt IS NULL ORDER BY r.createdAt ASC, r.userId ASC")
    LiveData<List<MessageReactionEntity>> observeByConversation(String conversationId);

    @Query("SELECT * FROM message_reaction WHERE messageServerId = :messageServerId ORDER BY createdAt ASC, userId ASC")
    List<MessageReactionEntity> findByMessageSync(long messageServerId);
}
