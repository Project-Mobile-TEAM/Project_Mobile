package com.utechat.data.local;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Kiểm thử trên SQLite/FTS4 thực tế các bất biến cốt lõi của Phase 5:
 * - Tìm kiếm tiếng Việt có/không dấu, chặn lỗi cú pháp FTS4 MATCH và tự xóa chỉ mục khi dọn cache.
 * - Cô lập chặt lịch sử nhóm theo visibleFromMessageId (>), bảo vệ tin cục bộ SENDING/FAILED (serverId IS NULL).
 * - Tính đơn điệu của biên nhận (READ > DELIVERED > SENT) và bảo vệ markFailed / deleteFailedLocal.
 */
@RunWith(AndroidJUnit4.class)
public class MessagingRoomAndFtsTest {
    private static final String ME = "u-me";
    private static final String PEER = "u-peer";

    private AppDatabase db;

    @Before
    public void open() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
    }

    @After
    public void close() {
        db.close();
    }

    @Test
    public void ftsSearchesAccentedUnaccentedSpecialCharsAndCleansDeletedCache() {
        insertUser(ME, "Tôi");
        insertUser(PEER, "Nguyễn Văn Đặng");

        // Hội thoại DIRECT không có conversation.title -> phải giải tên qua peerUser.displayName.
        insertConversation("conv-direct", "DIRECT", null, 2000L);
        insertMember("conv-direct", ME, "MEMBER", null);
        insertMember("conv-direct", PEER, "MEMBER", null);

        // Hội thoại GROUP có tiêu đề tường minh.
        insertConversation("conv-group", "GROUP", "Nhóm Đồ Án", 3000L);
        insertMember("conv-group", ME, "ADMIN", null);

        db.messageDao().insertRespectingVisibleFrom(
                message("c-1", 101L, "conv-direct", PEER, "Xin chào bạn Đặng", 2000L, "SENT", null, null), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-2", 102L, "conv-group", ME, "Họp nhóm đồ án", 3000L, "SENT", null, null), ME);

        // 1) Tìm trong từng phòng theo từ khóa không dấu.
        List<MessageEntity> inDirect = db.messageDao().searchInConversationVisible(
                "conv-direct", ME, MessageText.safeMatchQuery("chao dang"));
        assertEquals(1, inDirect.size());
        assertEquals("c-1", inDirect.get(0).getClientId());

        List<MessageEntity> inGroup = db.messageDao().searchInConversationVisible(
                "conv-group", ME, MessageText.safeMatchQuery("hop nhom"));
        assertEquals(1, inGroup.size());
        assertEquals("c-2", inGroup.get(0).getClientId());

        // 2) Tìm toàn cục: DIRECT phải tự lấy peerUser.displayName làm conversationTitle.
        List<MessageDao.MessageSearchHit> globalDirect = db.messageDao().searchGlobalVisible(
                ME, MessageText.safeMatchQuery("chao dang"), 20, 0);
        assertEquals(1, globalDirect.size());
        assertEquals("Nguyễn Văn Đặng", globalDirect.get(0).conversationTitle);
        assertEquals("DIRECT", globalDirect.get(0).conversationType);

        List<MessageDao.MessageSearchHit> globalGroup = db.messageDao().searchGlobalVisible(
                ME, MessageText.safeMatchQuery("Họp nhóm"), 20, 0);
        assertEquals(1, globalGroup.size());
        assertEquals("Nhóm Đồ Án", globalGroup.get(0).conversationTitle);
        assertEquals("GROUP", globalGroup.get(0).conversationType);

        // 3) Ký tự toán tử FTS4 đặc biệt không được gây SQLiteException mà trả về danh sách rỗng an toàn.
        String specialQuery = MessageText.safeMatchQuery("\" OR * ()");
        assertEquals("\"or\"*", specialQuery);
        assertTrue(db.messageDao().searchInConversationVisible("conv-direct", ME, specialQuery).isEmpty());

        String punctuationOnly = MessageText.safeMatchQuery("\"\"*():-");
        assertNull(punctuationOnly);
        assertTrue(db.messageDao().searchInConversationVisible("conv-direct", ME, punctuationOnly).isEmpty());
        assertTrue(db.messageDao().searchGlobalVisible(ME, punctuationOnly, 20, 0).isEmpty());

        // 4) Dọn tin cũ khỏi bảng message phải tự xóa luôn chỉ mục trong message_fts qua trigger BEFORE DELETE.
        db.messageDao().purgeBeforeVisibleId("conv-direct", 101L);
        db.messageDao().purgeBeforeVisibleId("conv-group", 102L);
        assertEquals(0, db.messageDao().searchInConversationVisible("conv-direct", ME, MessageText.safeMatchQuery("chao dang")).size());
        assertEquals(0, db.messageDao().searchGlobalVisible(ME, MessageText.safeMatchQuery("hop nhom"), 20, 0).size());
    }

    @Test
    public void strictVisibleFromMessageIdIsolatesHistoryAndProtectsPendingFailed() throws Exception {
        insertUser(ME, "Tôi");
        insertUser(PEER, "Trần Bình");

        insertConversation("conv-direct", "DIRECT", null, 5000L);
        insertMember("conv-direct", ME, "MEMBER", null);
        insertMember("conv-direct", PEER, "MEMBER", null);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-direct", 50L, "conv-direct", PEER, "Tin nhắn 1-1", 5000L, "SENT", null, null), ME);

        insertConversation("conv-g", "GROUP", "Nhóm Kỹ Thuật", 4000L);
        insertMember("conv-g", ME, "MEMBER", null);

        db.messageDao().insertRespectingVisibleFrom(
                message("c-10", 10L, "conv-g", PEER, "Lịch sử cũ 10", 1000L, "SENT", null, null), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-11", 11L, "conv-g", PEER, "Mốc đúng bằng 11", 2000L, "SENT", null, null), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-12", 12L, "conv-g", PEER, "Tin sau khi vào lại 12", 3000L, "SENT", null, null), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-failed", null, "conv-g", ME, "Tin lỗi cục bộ", 3500L, "FAILED", "NETWORK", 0L), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-sending", null, "conv-g", ME, "Tin đang gửi kỳ cũ", 3600L, "SENDING", null, 5L), ME);

        // Nâng mốc visibleFromMessageId lên 11 và dọn toàn bộ tin serverId <= 11.
        ConversationMemberEntity updatedMember = new ConversationMemberEntity();
        updatedMember.setConversationId("conv-g");
        updatedMember.setUserId(ME);
        updatedMember.setRole("MEMBER");
        updatedMember.setVisibleFromMessageId(11L);
        db.conversationDao().upsertMemberMonotonic(updatedMember);
        db.messageDao().purgeBeforeVisibleId("conv-g", 11L);
        db.conversationDao().recomputeLastMessageAfterPurge("conv-g", 11L);

        // Cả 10 và 11 (bằng đúng cutoff!) đều phải bị xóa khỏi message và message_fts; 12, FAILED, SENDING còn nguyên.
        assertNull(db.messageDao().findByClientId("c-10"));
        assertNull(db.messageDao().findByClientId("c-11"));
        assertEquals(0, db.messageDao().searchInConversationVisible("conv-g", ME, MessageText.safeMatchQuery("Lịch sử cũ")).size());
        assertEquals(0, db.messageDao().searchInConversationVisible("conv-g", ME, MessageText.safeMatchQuery("Mốc đúng bằng")).size());
        assertNotNull(db.messageDao().findByClientId("c-12"));
        assertNotNull(db.messageDao().findByClientId("c-failed"));
        assertEquals("FAILED", db.messageDao().findByClientId("c-failed").getStatus());
        assertEquals(Long.valueOf(5L), db.messageDao().findByClientId("c-sending").getQueuedVisibleFromMessageId());

        // Kiểm tra observeConversationSummaries giải peerDisplayName cho DIRECT và dùng tin hợp lệ mới nhất cho GROUP.
        List<ConversationListItem> summaries = getOrAwaitValue(db.conversationDao().observeConversationSummaries(ME));
        assertEquals(2, summaries.size());
        ConversationListItem directItem = summaries.get(0);
        assertEquals("conv-direct", directItem.conversation.getId());
        assertEquals("Trần Bình", directItem.getEffectiveTitle());
        assertEquals("Tin nhắn 1-1", directItem.lastMessageText);

        ConversationListItem groupItem = summaries.get(1);
        assertEquals("conv-g", groupItem.conversation.getId());
        assertEquals("Nhóm Kỹ Thuật", groupItem.getEffectiveTitle());
        // Tin mới nhất hợp lệ lúc này là c-sending (createdAt=3600, serverId=null), tuyệt đối không lộ tin 10 hay 11.
        assertEquals("Tin đang gửi kỳ cũ", groupItem.lastMessageText);

        // insertRespectingVisibleFrom phải từ chối chèn lại tin trễ 10 và 11, nhưng chấp nhận 13.
        assertFalse(db.messageDao().insertRespectingVisibleFrom(
                message("c-late-10", 10L, "conv-g", PEER, "Lịch sử cũ 10", 1000L, "SENT", null, null), ME));
        assertFalse(db.messageDao().insertRespectingVisibleFrom(
                message("c-late-11", 11L, "conv-g", PEER, "Mốc đúng bằng 11", 2000L, "SENT", null, null), ME));
        assertTrue(db.messageDao().insertRespectingVisibleFrom(
                message("c-13", 13L, "conv-g", PEER, "Tin mới 13", 4500L, "SENT", null, null), ME));
        assertNotNull(db.messageDao().findByClientId("c-13"));

        // upsertMemberMonotonic không bao giờ hạ visibleFromMessageId từ 11 xuống 5 hay null.
        ConversationMemberEntity staleMember = new ConversationMemberEntity();
        staleMember.setConversationId("conv-g");
        staleMember.setUserId(ME);
        staleMember.setRole("MEMBER");
        staleMember.setVisibleFromMessageId(5L);
        db.conversationDao().upsertMemberMonotonic(staleMember);
        assertEquals(Long.valueOf(11L), db.conversationDao().getMemberSync("conv-g", ME).getVisibleFromMessageId());

        // Khi hợp nhất ACK của tin SENDING, trường queuedVisibleFromMessageId cục bộ vẫn được giữ nguyên (5L).
        assertTrue(db.messageDao().insertRespectingVisibleFrom(
                message("c-sending", 14L, "conv-g", ME, "Tin đang gửi kỳ cũ", 4600L, "SENT", null, null), ME));
        MessageEntity ackedSending = db.messageDao().findByClientId("c-sending");
        assertNotNull(ackedSending);
        assertEquals(Long.valueOf(14L), ackedSending.getServerId());
        assertEquals(Long.valueOf(5L), ackedSending.getQueuedVisibleFromMessageId());
    }

    @Test
    public void monotonicReceiptStatusAndServerIdNullMarkFailedGuard() {
        insertConversation("conv-1", "DIRECT", null, 1000L);
        insertMember("conv-1", ME, "MEMBER", null);

        // Chèn tin đang gửi bị gắn errorCode tạm, sau đó giả lập tin đã đạt trạng thái READ từ server.
        db.messageDao().insertRespectingVisibleFrom(
                message("c-read", 200L, "conv-1", ME, "Xin chào", 1000L, "READ", "TEMP_ERR", null), ME);
        MessageEntity saved = db.messageDao().findByClientId("c-read");
        assertNotNull(saved);
        assertNull(saved.getErrorCode());
        assertEquals("READ", saved.getStatus());

        // Replay trạng thái thấp hơn (SENT) không được hạ READ xuống SENT.
        assertTrue(db.messageDao().insertRespectingVisibleFrom(
                message("c-read", 200L, "conv-1", ME, "Xin chào", 1000L, "SENT", null, null), ME));
        assertEquals("READ", db.messageDao().findByClientId("c-read").getStatus());

        // markSentFromWorker phải xóa errorCode = null và giữ nguyên trạng thái DELIVERED/READ.
        db.messageDao().insertRespectingVisibleFrom(
                message("c-worker", null, "conv-1", ME, "Gửi qua worker", 1100L, "FAILED", "NETWORK", null), ME);
        db.messageDao().markSentFromWorker("c-worker", 201L, 1200L);
        MessageEntity sentByWorker = db.messageDao().findByClientId("c-worker");
        assertEquals(Long.valueOf(201L), sentByWorker.getServerId());
        assertEquals("SENT", sentByWorker.getStatus());
        assertNull(sentByWorker.getErrorCode());

        // Nâng đơn điệu lên DELIVERED rồi READ; nâng lại DELIVERED không được hạ bậc từ READ.
        db.messageDao().upgradeReceiptsForSender("conv-1", ME, 201L, "DELIVERED");
        assertEquals("DELIVERED", db.messageDao().findByClientId("c-worker").getStatus());
        db.messageDao().upgradeReceiptsForSender("conv-1", ME, 201L, "READ");
        assertEquals("READ", db.messageDao().findByClientId("c-worker").getStatus());
        db.messageDao().upgradeReceiptsForSender("conv-1", ME, 201L, "DELIVERED");
        assertEquals("READ", db.messageDao().findByClientId("c-worker").getStatus());

        // Gọi lại markSentFromWorker khi tin đã READ vẫn phải giữ READ.
        db.messageDao().markSentFromWorker("c-worker", 201L, 1200L);
        assertEquals("READ", db.messageDao().findByClientId("c-worker").getStatus());

        // markFailed chỉ được đánh dấu tin chưa có serverId (serverId IS NULL).
        int affectedAcked = db.messageDao().markFailed("c-worker", "NETWORK");
        assertEquals(0, affectedAcked);
        assertEquals("READ", db.messageDao().findByClientId("c-worker").getStatus());
        assertNull(db.messageDao().findByClientId("c-worker").getErrorCode());
    }

    @Test
    public void deleteFailedLocalOnlyDeletesUnsentFailedMessages() {
        insertConversation("conv-1", "DIRECT", null, 1000L);
        insertMember("conv-1", ME, "MEMBER", null);

        db.messageDao().insertRespectingVisibleFrom(
                message("c-failed-local", null, "conv-1", ME, "Lỗi cục bộ", 1000L, "FAILED", "BLOCKED", null), ME);
        db.messageDao().insertRespectingVisibleFrom(
                message("c-sent-server", 300L, "conv-1", ME, "Đã gửi server", 1100L, "SENT", null, null), ME);

        assertEquals(1, db.messageDao().deleteFailedLocal("c-failed-local"));
        assertNull(db.messageDao().findByClientId("c-failed-local"));

        // Không được xóa tin đã lên server (serverId != null / status = SENT).
        assertEquals(0, db.messageDao().deleteFailedLocal("c-sent-server"));
        assertNotNull(db.messageDao().findByClientId("c-sent-server"));
    }

    private void insertUser(String id, String displayName) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setAllowMessagesFromNonContacts(true);
        db.userDao().upsertPreservingPrivacy(user);
    }

    private void insertConversation(String id, String type, String title, long lastMessageAt) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTitle(title);
        conversation.setLastMessageAt(lastMessageAt);
        db.conversationDao().insert(conversation);
    }

    private void insertMember(String convId, String userId, String role, Long visibleFrom) {
        ConversationMemberEntity member = new ConversationMemberEntity();
        member.setConversationId(convId);
        member.setUserId(userId);
        member.setRole(role);
        member.setVisibleFromMessageId(visibleFrom);
        db.conversationDao().insertMember(member);
    }

    private MessageEntity message(String clientId, Long serverId, String convId, String senderId,
                                  String text, long createdAt, String status, String errorCode,
                                  Long queuedVisibleFrom) {
        MessageEntity msg = new MessageEntity();
        msg.setClientId(clientId);
        msg.setServerId(serverId);
        msg.setConversationId(convId);
        msg.setSenderId(senderId);
        msg.setType("TEXT");
        msg.setText(text);
        msg.setCreatedAt(createdAt);
        msg.setStatus(status);
        msg.setErrorCode(errorCode);
        msg.setQueuedVisibleFromMessageId(queuedVisibleFrom);
        return msg;
    }

    /** Chờ lấy giá trị đầu tiên của LiveData trên luồng chính Android mà không dùng ArchTaskExecutor. */
    private static <T> T getOrAwaitValue(LiveData<T> liveData) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Observer<T> observer = new Observer<T>() {
            @Override
            public void onChanged(T value) {
                result.set(value);
                liveData.removeObserver(this);
                latch.countDown();
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> liveData.observeForever(observer));
        assertTrue("Timeout chờ LiveData phát giá trị", latch.await(3, TimeUnit.SECONDS));
        return result.get();
    }
}
