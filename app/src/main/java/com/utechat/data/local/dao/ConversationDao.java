package com.utechat.data.local.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;

/** ConversationDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface ConversationDao {
    // Sắp xếp theo thời gian để cả tin đang chờ serverId vẫn có thể lên đầu.
    @Upsert void insert(ConversationEntity conversation);
    @Upsert void insertMember(ConversationMemberEntity member);
    @Query("SELECT * FROM conversation ORDER BY lastMessageAt DESC") LiveData<List<ConversationEntity>> getAllConversations();
    @Query("SELECT * FROM member WHERE conversationId = :id") LiveData<List<ConversationMemberEntity>> getMembers(String id);

    /**
     * Quan sát danh sách hội thoại đầy đủ gồm tên/ảnh đối phương (chat 1-1) và tin nhắn cuối hợp lệ.
     * Lọc chặt (serverId IS NULL OR serverId > COALESCE(visibleFromMessageId, 0)) để không lộ tin cũ trước khi vào lại nhóm.
     */
    @Query("SELECT c.*, "
            + "peerMb.userId AS peerUserId, "
            + "peerUser.displayName AS peerDisplayName, "
            + "peerUser.avatarUrl AS peerAvatarUrl, "
            + "lm.type AS lastMessageType, "
            + "lm.text AS lastMessageText, "
            + "lm.attachmentJson AS lastMessageAttachmentJson, "
            + "lm.senderId AS lastMessageSenderId, "
            + "lm.status AS lastMessageStatus, "
            + "lm.serverId AS lastMessageServerId, "
            + "lm.recalledAt AS lastMessageRecalledAt, "
            + "lr.emoji AS latestReactionEmoji, "
            + "lr.userId AS latestReactionUserId, "
            + "lr.createdAt AS latestReactionAt "
            + "FROM conversation c "
            + "JOIN member myMb ON myMb.conversationId = c.id AND myMb.userId = :myUserId "
            + "LEFT JOIN member peerMb ON peerMb.conversationId = c.id AND c.type = 'DIRECT' AND peerMb.userId <> :myUserId "
            + "LEFT JOIN user_cache peerUser ON peerUser.id = peerMb.userId "
            + "LEFT JOIN message lm ON lm.localId = ("
            + "  SELECT m.localId FROM message m "
            + "  WHERE m.conversationId = c.id "
            + "    AND (m.serverId IS NULL OR m.serverId > COALESCE(myMb.visibleFromMessageId, 0)) "
            + "  ORDER BY m.createdAt DESC, m.localId DESC "
            + "  LIMIT 1"
            + ") "
            + "LEFT JOIN message_reaction lr ON lr.rowid = ("
            + "  SELECT r.rowid FROM message_reaction r "
            + "  INNER JOIN message rm ON rm.serverId = r.messageServerId "
            + "  WHERE rm.conversationId = c.id "
            + "    AND rm.recalledAt IS NULL "
            + "    AND r.userId <> :myUserId "
            + "    AND rm.serverId > COALESCE(myMb.visibleFromMessageId, 0) "
            + "    AND NOT EXISTS (SELECT 1 FROM message_deletion d WHERE d.serverId = rm.serverId) "
            + "  ORDER BY r.createdAt DESC, r.messageServerId DESC, r.userId ASC "
            + "  LIMIT 1"
            + ") "
            + "ORDER BY c.lastMessageAt DESC, c.id ASC")
    LiveData<List<ConversationListItem>> observeConversationSummaries(String myUserId);

    /** Đọc đồng bộ một hội thoại theo UUID trên luồng đĩa. */
    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    ConversationEntity getConversationSync(String id);

    /** Quan sát thông tin một hội thoại theo thời gian thực cho màn hình phòng chat và chi tiết nhóm. */
    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    LiveData<ConversationEntity> observeConversation(String id);

    /** Đọc đồng bộ toàn bộ danh sách hội thoại trên luồng đĩa. */
    @Query("SELECT * FROM conversation ORDER BY lastMessageAt DESC, id ASC")
    List<ConversationEntity> getAllConversationsListSync();

    /** Đọc đồng bộ danh sách thành viên của hội thoại. */
    @Query("SELECT * FROM member WHERE conversationId = :conversationId")
    List<ConversationMemberEntity> getMembersSync(String conversationId);

    /** Đọc đồng bộ bản ghi thành viên cụ thể trong hội thoại. */
    @Query("SELECT * FROM member WHERE conversationId = :conversationId AND userId = :userId LIMIT 1")
    ConversationMemberEntity getMemberSync(String conversationId, String userId);

    /** Tìm hội thoại DIRECT hiện có với một đối phương cụ thể trong cache cục bộ. */
    @Query("SELECT c.* FROM conversation c "
            + "JOIN member m ON m.conversationId = c.id "
            + "WHERE c.type = 'DIRECT' AND m.userId = :peerUserId "
            + "LIMIT 1")
    ConversationEntity findDirectConversationWithPeer(String peerUserId);

    /**
     * Lưu thông tin thành viên theo quy tắc đơn điệu:
     * không bao giờ hạ thấp visibleFromMessageId, lastDeliveredId hay lastReadId khi nhận sự kiện đến trễ.
     */
    @Transaction
    default void upsertMemberMonotonic(ConversationMemberEntity incoming) {
        if (incoming == null) return;
        ConversationMemberEntity old = getMemberSync(incoming.getConversationId(), incoming.getUserId());
        ConversationMemberEntity merged = new ConversationMemberEntity();
        merged.setConversationId(incoming.getConversationId());
        merged.setUserId(incoming.getUserId());
        merged.setRole(incoming.getRole());
        merged.setLeft(incoming.getLeft());
        merged.setLastDeliveredId(incoming.getLastDeliveredId());
        merged.setLastReadId(incoming.getLastReadId());
        merged.setVisibleFromMessageId(incoming.getVisibleFromMessageId());
        if (old != null) {
            merged.setLastDeliveredId(Math.max(old.getLastDeliveredId(), incoming.getLastDeliveredId()));
            merged.setLastReadId(Math.max(old.getLastReadId(), incoming.getLastReadId()));
            Long oldVisible = old.getVisibleFromMessageId();
            Long newVisible = incoming.getVisibleFromMessageId();
            if (oldVisible != null && newVisible != null) {
                merged.setVisibleFromMessageId(Math.max(oldVisible, newVisible));
            } else if (oldVisible != null) {
                // Giữ mốc giới hạn cũ, tuyệt đối không hạ xuống null khi gói tin trễ thiếu trường này.
                merged.setVisibleFromMessageId(oldVisible);
            }
        }
        insertMember(merged);
    }

    /** Đánh dấu một thành viên đã rời khỏi hội thoại. */
    @Query("UPDATE member SET left = 1 WHERE conversationId = :conversationId AND userId = :userId")
    void markMemberLeft(String conversationId, String userId);

    /** Đánh dấu đã rời tất cả các nhóm khi bootstrap trả về danh sách nhóm đang tham gia rỗng. */
    @Query("UPDATE member SET left = 1 WHERE userId = :myUserId AND conversationId IN (SELECT id FROM conversation WHERE type = 'GROUP')")
    void markAllGroupsLeftInternal(String myUserId);

    /** Đánh dấu đã rời các nhóm cục bộ không còn xuất hiện trong ảnh chụp bootstrap mới nhất. */
    @Query("UPDATE member SET left = 1 WHERE userId = :myUserId AND conversationId IN (SELECT id FROM conversation WHERE type = 'GROUP') AND conversationId NOT IN (:activeConversationIds)")
    void markOtherGroupsLeftInternal(List<String> activeConversationIds, String myUserId);

    /**
     * Đối soát trạng thái rời nhóm sau bootstrap: bất kỳ nhóm cũ nào không nằm trong activeConversationIds
     * đều được đánh dấu left = 1 mà không xóa dữ liệu cache một cách đột ngột.
     */
    @Transaction
    default void markOtherGroupsLeftIfMissing(@Nullable List<String> activeConversationIds, String myUserId) {
        if (activeConversationIds == null || activeConversationIds.isEmpty()) {
            markAllGroupsLeftInternal(myUserId);
        } else {
            markOtherGroupsLeftInternal(activeConversationIds, myUserId);
        }
    }

    /** Cập nhật tiêu đề, ảnh đại diện và thông tin tin nhắn cuối của hội thoại. */
    @Query("UPDATE conversation SET title = :title, avatarUrl = :avatarUrl, lastMessageId = :lastMessageId, lastMessageAt = :lastMessageAt WHERE id = :conversationId")
    void updateTitleAndLastMessage(String conversationId, @Nullable String title, @Nullable String avatarUrl, @Nullable Long lastMessageId, long lastMessageAt);

    /** Cập nhật mốc tin cuối sau khi xóa tin phía mình, kể cả khi cần lùi về tin cũ. */
    @Query("UPDATE conversation SET lastMessageId = :lastMessageId, lastMessageAt = :lastMessageAt WHERE id = :conversationId")
    void updateLastMessageMeta(String conversationId, @Nullable Long lastMessageId, long lastMessageAt);

    /** Cập nhật mốc tin nhắn cuối chỉ khi tin mới có serverId/thời gian lớn hơn hoặc bằng mốc hiện tại. */
    @Query("UPDATE conversation SET "
            + "lastMessageId = CASE WHEN lastMessageId IS NULL OR :messageId >= lastMessageId THEN :messageId ELSE lastMessageId END, "
            + "lastMessageAt = CASE WHEN :messageAt >= lastMessageAt THEN :messageAt ELSE lastMessageAt END "
            + "WHERE id = :conversationId")
    void updateLastMessageIfNewer(String conversationId, long messageId, long messageAt);

    /** Tính lại lastMessageId và lastMessageAt sau khi dọn các tin nhắn cũ <= visibleFrom. */
    @Query("UPDATE conversation SET "
            + "lastMessageId = ("
            + "  SELECT m.serverId FROM message m "
            + "  WHERE m.conversationId = :conversationId AND m.serverId IS NOT NULL AND m.serverId > :visibleFrom "
            + "  ORDER BY m.serverId DESC LIMIT 1"
            + "), "
            + "lastMessageAt = COALESCE(("
            + "  SELECT m.createdAt FROM message m "
            + "  WHERE m.conversationId = :conversationId AND (m.serverId IS NULL OR m.serverId > :visibleFrom) "
            + "  ORDER BY m.createdAt DESC, m.localId DESC LIMIT 1"
            + "), lastMessageAt) "
            + "WHERE id = :conversationId")
    void recomputeLastMessageAfterPurge(String conversationId, long visibleFrom);

    /** Cập nhật tùy chọn tắt thông báo của hội thoại. */
    @Query("UPDATE conversation SET muted = :muted WHERE id = :conversationId")
    void updateMuted(String conversationId, boolean muted);

    /** Đặt lại số lượng tin chưa đọc cụ thể cho hội thoại. */
    @Query("UPDATE conversation SET unreadCount = :unreadCount WHERE id = :conversationId")
    void setUnreadCount(String conversationId, int unreadCount);

    /** Tăng số tin chưa đọc lên 1 khi có tin nhắn đến từ người khác. */
    @Query("UPDATE conversation SET unreadCount = unreadCount + 1 WHERE id = :conversationId")
    void incrementUnread(String conversationId);
}
