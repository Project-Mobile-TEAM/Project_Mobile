package com.utechat.data.local.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.Collections;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** MessageDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface MessageDao {
    /**
     * Kết quả tìm kiếm tin nhắn toàn cục kèm tên và loại hội thoại tương ứng.
     * Với chat DIRECT, conversationTitle tự động rơi về tên đối phương từ user_cache.
     */
    class MessageSearchHit {
        @Embedded
        public MessageEntity message;

        @Nullable
        public String conversationTitle;

        @Nullable
        public String conversationType;
    }

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

    /**
     * Quan sát toàn bộ tin nhắn hợp lệ của hội thoại theo thứ tự thời gian tăng dần.
     * Luôn giữ tin cục bộ (serverId IS NULL) và lọc chặt serverId > COALESCE(visibleFromMessageId, 0).
     */
    @Query("SELECT m.* FROM message m "
            + "LEFT JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
            + "WHERE m.conversationId = :conversationId "
            + "AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
            + "ORDER BY COALESCE(m.serverId, 9223372036854775807) ASC, m.createdAt ASC, m.localId ASC")
    LiveData<List<MessageEntity>> getVisibleMessages(String conversationId, String myUserId);

    /**
     * Lấy cửa sổ gồm :limit tin nhắn hợp lệ mới nhất, sau đó sắp xếp tăng dần để hiển thị trực tiếp lên RecyclerView.
     */
    @Query("SELECT * FROM ("
            + "  SELECT m.* FROM message m "
            + "  LEFT JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
            + "  WHERE m.conversationId = :conversationId "
            + "  AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
            + "  ORDER BY COALESCE(m.serverId, 9223372036854775807) DESC, m.createdAt DESC, m.localId DESC "
            + "  LIMIT :limit"
            + ") "
            + "ORDER BY COALESCE(serverId, 9223372036854775807) ASC, createdAt ASC, localId ASC")
    LiveData<List<MessageEntity>> getVisibleMessagesWindow(String conversationId, String myUserId, int limit);

    /** Đếm số tin nhắn hợp lệ đứng sau (mới hơn) một localId mục tiêu để mở rộng windowLimit khi nhảy tới kết quả tìm kiếm. */
    @Query("SELECT COUNT(*) FROM message m "
            + "LEFT JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
            + "JOIN message t ON t.localId = :targetLocalId "
            + "WHERE m.conversationId = :conversationId "
            + "AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
            + "AND ("
            + "  COALESCE(m.serverId, 9223372036854775807) > COALESCE(t.serverId, 9223372036854775807) "
            + "  OR (COALESCE(m.serverId, 9223372036854775807) = COALESCE(t.serverId, 9223372036854775807) AND m.createdAt > t.createdAt) "
            + "  OR (COALESCE(m.serverId, 9223372036854775807) = COALESCE(t.serverId, 9223372036854775807) AND m.createdAt = t.createdAt AND m.localId > t.localId)"
            + ")")
    int countVisibleMessagesNewerThan(String conversationId, String myUserId, long targetLocalId);

    /** Tra cứu tin nhắn theo khóa cục bộ localId. */
    @Query("SELECT * FROM message WHERE localId = :localId LIMIT 1")
    MessageEntity findByLocalId(long localId);

    /** Truy vấn SQLite FTS4 trong phạm vi một hội thoại (chỉ gọi sau khi safeQuery đã hợp lệ). */
    @Query("SELECT m.* FROM message m "
            + "JOIN message_fts ON m.localId = message_fts.rowid "
            + "LEFT JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
            + "WHERE message_fts MATCH :safeQuery "
            + "AND m.conversationId = :conversationId "
            + "AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
            + "ORDER BY m.createdAt DESC, m.localId DESC")
    List<MessageEntity> searchInConversationVisibleInternal(String conversationId, String myUserId, String safeQuery);

    /**
     * Tìm kiếm FTS4 an toàn trong một hội thoại: trả danh sách rỗng nếu safeQuery là null/rỗng
     * để tránh SQLiteException khi người dùng nhập chuỗi rỗng hoặc toàn ký tự dấu câu.
     */
    default List<MessageEntity> searchInConversationVisible(String conversationId, String myUserId, @Nullable String safeQuery) {
        if (safeQuery == null || safeQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return searchInConversationVisibleInternal(conversationId, myUserId, safeQuery);
    }

    /** Truy vấn SQLite FTS4 toàn cục qua mọi hội thoại người dùng tham gia, tự giải tên hội thoại DIRECT. */
    @Query("SELECT m.*, "
            + "COALESCE(c.title, peerUser.displayName) AS conversationTitle, "
            + "c.type AS conversationType "
            + "FROM message m "
            + "JOIN message_fts ON m.localId = message_fts.rowid "
            + "JOIN conversation c ON c.id = m.conversationId "
            + "JOIN member mb ON mb.conversationId = m.conversationId AND mb.userId = :myUserId "
            + "LEFT JOIN member peerMb ON peerMb.conversationId = c.id AND c.type = 'DIRECT' AND peerMb.userId <> :myUserId "
            + "LEFT JOIN user_cache peerUser ON peerUser.id = peerMb.userId "
            + "WHERE message_fts MATCH :safeQuery "
            + "AND (m.serverId IS NULL OR m.serverId > COALESCE(mb.visibleFromMessageId, 0)) "
            + "ORDER BY m.createdAt DESC, m.localId DESC "
            + "LIMIT :limit OFFSET :offset")
    List<MessageSearchHit> searchGlobalVisibleInternal(String myUserId, String safeQuery, int limit, int offset);

    /** Tìm kiếm FTS4 an toàn trên toàn bộ hội thoại; bỏ qua MATCH khi safeQuery là null/rỗng. */
    default List<MessageSearchHit> searchGlobalVisible(String myUserId, @Nullable String safeQuery, int limit, int offset) {
        if (safeQuery == null || safeQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return searchGlobalVisibleInternal(myUserId, safeQuery, limit, offset);
    }

    /**
     * Xóa tin nhắn đã lưu từ server có serverId <= visibleFrom để cô lập lịch sử nhóm;
     * điều kiện AND serverId IS NOT NULL bảo vệ nguyên vẹn tin SENDING/FAILED cục bộ.
     * Trigger BEFORE DELETE của Room sẽ tự động xóa chỉ mục tương ứng trong message_fts.
     */
    @Query("DELETE FROM message WHERE conversationId = :conversationId AND serverId IS NOT NULL AND serverId <= :visibleFrom")
    void purgeBeforeVisibleId(String conversationId, long visibleFrom);

    /** Đọc mốc visibleFromMessageId hiện tại của người dùng trong hội thoại. */
    @Query("SELECT visibleFromMessageId FROM member WHERE conversationId = :conversationId AND userId = :myUserId LIMIT 1")
    Long findMemberVisibleFrom(String conversationId, String myUserId);

    /**
     * Chèn hoặc hợp nhất tin nhắn có kiểm tra chặt mốc visibleFromMessageId:
     * - Từ chối tin server có serverId <= visibleFromMessageId (trả false).
     * - Bảo toàn localId, queuedVisibleFromMessageId cục bộ và trạng thái biên nhận đơn điệu (READ > DELIVERED > SENT).
     * - Tự động xóa errorCode = null khi tin đã có serverId từ server.
     */
    @Transaction
    default boolean insertRespectingVisibleFrom(MessageEntity message, String myUserId) {
        if (message == null) return false;
        if (message.getServerId() != null) {
            Long visibleFrom = findMemberVisibleFrom(message.getConversationId(), myUserId);
            if (visibleFrom != null && message.getServerId() <= visibleFrom) {
                return false;
            }
        }
        MessageEntity existing = findByClientId(message.getClientId());
        if (existing == null && message.getServerId() != null) {
            existing = findByServerId(message.getServerId());
        }

        MessageEntity merged = new MessageEntity();
        merged.setClientId(message.getClientId());
        merged.setServerId(message.getServerId());
        merged.setConversationId(message.getConversationId());
        merged.setSenderId(message.getSenderId());
        merged.setType(message.getType());
        merged.setText(message.getText());
        merged.setAttachmentJson(message.getAttachmentJson());
        merged.setCreatedAt(message.getCreatedAt());
        merged.setStatus(message.getStatus());
        merged.setErrorCode(message.getErrorCode());
        merged.setQueuedVisibleFromMessageId(message.getQueuedVisibleFromMessageId());

        if (existing != null) {
            merged.setLocalId(existing.getLocalId());
            if (merged.getServerId() == null && existing.getServerId() != null) {
                merged.setServerId(existing.getServerId());
            }
            // Giữ nguyên mốc queuedVisibleFromMessageId cục bộ qua các đợt purge hoặc ACK từ server.
            if (existing.getQueuedVisibleFromMessageId() != null) {
                merged.setQueuedVisibleFromMessageId(existing.getQueuedVisibleFromMessageId());
            }
            // Không hạ bậc trạng thái biên nhận khi sự kiện cũ hoặc replay đi tới sau.
            if (statusRank(existing.getStatus()) > statusRank(merged.getStatus())) {
                merged.setStatus(existing.getStatus());
            }
        }

        if (merged.getServerId() != null) {
            merged.setErrorCode(null);
            if (statusRank(merged.getStatus()) < statusRank("SENT")) {
                merged.setStatus("SENT");
            }
        }

        merged.setNormalizedText(MessageText.normalize(merged.getText()));
        upsert(merged);
        return true;
    }

    /** Chuyển trạng thái tin nhắn thành thứ bậc số để so sánh đơn điệu. */
    static int statusRank(@Nullable String status) {
        if ("READ".equals(status)) return 4;
        if ("DELIVERED".equals(status)) return 3;
        if ("SENT".equals(status)) return 2;
        if ("SENDING".equals(status)) return 1;
        return 0;
    }

    /** Lấy các tin nhắn loại CALL hiện có trong cache để cập nhật metadata CALL_UPDATED. */
    @Query("SELECT * FROM message WHERE type = 'CALL'")
    List<MessageEntity> findCallMessagesSync();

    /** Cập nhật metadata JSON của một tin nhắn mà không làm thay đổi định danh hay trạng thái gửi. */
    @Query("UPDATE message SET attachmentJson = :json WHERE localId = :localId")
    void updateAttachmentJson(long localId, @Nullable String json);

    /** Tra cứu tin nhắn theo clientId. */
    @Query("SELECT * FROM message WHERE clientId = :clientId LIMIT 1")
    MessageEntity findByClientId(String clientId);

    /** Tra cứu tin nhắn theo serverId. */
    @Query("SELECT * FROM message WHERE serverId = :serverId LIMIT 1")
    MessageEntity findByServerId(long serverId);

    /** Tìm serverId nhỏ nhất hiện có trong hội thoại để làm cursor beforeId khi tải lịch sử cũ hơn. */
    @Query("SELECT MIN(serverId) FROM message WHERE conversationId = :conversationId AND serverId IS NOT NULL")
    Long findOldestServerId(String conversationId);

    /** Tìm serverId mới nhất từ người khác trong hội thoại để gửi mốc DELIVERED/READ. */
    @Query("SELECT MAX(serverId) FROM message WHERE conversationId = :conversationId AND serverId IS NOT NULL AND senderId <> :myUserId")
    Long findLatestOtherMessageId(String conversationId, String myUserId);

    /** Đếm số tin nhắn đến từ người khác vượt trên ngưỡng max(lastReadId, visibleFromMessageId) để tính lại unread cục bộ. */
    @Query("SELECT COUNT(*) FROM message "
            + "WHERE conversationId = :conversationId "
            + "AND senderId <> :myUserId "
            + "AND serverId IS NOT NULL "
            + "AND serverId > :floorMessageId")
    int countIncomingAfter(String conversationId, String myUserId, long floorMessageId);

    /**
     * Đánh dấu tin đã gửi thành công từ SendMessageWorker:
     * gán serverId/createdAt, xóa errorCode = NULL và không hạ bậc nếu tin đã ở DELIVERED hoặc READ.
     */
    @Query("UPDATE message SET "
            + "serverId = :serverId, "
            + "createdAt = :createdAt, "
            + "errorCode = NULL, "
            + "status = CASE WHEN status IN ('DELIVERED', 'READ') THEN status ELSE 'SENT' END "
            + "WHERE clientId = :clientId")
    void markSentFromWorker(String clientId, long serverId, long createdAt);

    /**
     * Đánh dấu tin nhắn thất bại kèm mã lỗi bền vững;
     * khóa cứng AND serverId IS NULL để timeout mạng không bao giờ ghi đè tin đã có ACK từ server.
     */
    @Query("UPDATE message SET status = 'FAILED', errorCode = :errorCode WHERE clientId = :clientId AND serverId IS NULL")
    int markFailed(String clientId, @Nullable String errorCode);

    /**
     * Đưa tin FAILED cục bộ quay lại trạng thái SENDING khi người dùng chủ động bấm "Gửi lại",
     * đồng thời chốt mốc queuedVisibleFromMessageId mới của lần tham gia hiện tại.
     */
    @Query("UPDATE message SET status = 'SENDING', errorCode = NULL, queuedVisibleFromMessageId = :queuedVisibleFromMessageId "
            + "WHERE clientId = :clientId AND status = 'FAILED' AND serverId IS NULL")
    int resetForRetry(String clientId, @Nullable Long queuedVisibleFromMessageId);

    /** Chỉ cho phép xóa cục bộ tin nhắn FAILED chưa từng lên server (serverId IS NULL). */
    @Query("DELETE FROM message WHERE clientId = :clientId AND serverId IS NULL AND status = 'FAILED'")
    int deleteFailedLocal(String clientId);

    /**
     * Nâng trạng thái biên nhận đơn điệu cho các tin nhắn của senderId có serverId <= upToServerId.
     * Tuyệt đối không hạ bậc từ READ xuống DELIVERED.
     */
    @Query("UPDATE message SET status = :targetStatus "
            + "WHERE conversationId = :conversationId "
            + "AND senderId = :senderId "
            + "AND serverId IS NOT NULL "
            + "AND serverId <= :upToServerId "
            + "AND ("
            + "  (:targetStatus = 'DELIVERED' AND status IN ('SENDING', 'SENT')) "
            + "  OR (:targetStatus = 'READ' AND status IN ('SENDING', 'SENT', 'DELIVERED'))"
            + ")")
    void upgradeReceiptsForSender(String conversationId, String senderId, long upToServerId, String targetStatus);
}
