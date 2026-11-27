package com.utechat.data.local;

import android.content.Context;
import android.database.Cursor;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageReactionEntity;
import com.utechat.data.local.entity.MessageTranslationEntity;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.local.entity.StickerPackEntity;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Dùng SQLite thật để kiểm trigger FTS4, fallback preview và cache tùy tài khoản. */
@RunWith(AndroidJUnit4.class)
public class Phase7RoomDaoTest {
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
    public void recallKeepsTimelineAndPurgesFullTextEvenAfterDelayedHistory() throws Exception {
        seedConversation();
        MessageEntity original = message(101, "Chúc mừng sinh nhật");
        original.setAttachmentJson("{\"stickerId\":\"1\"}");
        assertTrue(db.messageDao().insertRespectingVisibleFrom(original, "me"));
        assertEquals(1, matchCount("chuc"));

        // Gỡ tin giữ nguyên ID và xóa các trường nội dung cùng chỉ mục tìm kiếm.
        db.messageDao().markRecalledByServerId(101, 5000L);
        assertEquals(0, matchCount("chuc"));
        assertTrue(db.messageDao().searchGlobalVisible("me", MessageText.safeMatchQuery("chuc"), 10, 0).isEmpty());
        MessageEntity recalled = db.messageDao().findByServerId(101L);
        assertNull(recalled.getText());
        assertNull(recalled.getAttachmentJson());
        assertEquals(Long.valueOf(5000L), recalled.getRecalledAt());
        ConversationListItem summary = awaitValue(db.conversationDao().observeConversationSummaries("me")).get(0);
        assertEquals(Long.valueOf(5000L), summary.lastMessageRecalledAt);

        // Kết quả tải lịch sử cũ đến trễ không thể đưa chữ hoặc metadata đã gỡ trở lại.
        assertTrue(db.messageDao().insertRespectingVisibleFrom(original, "me"));
        MessageEntity merged = db.messageDao().findByServerId(101L);
        assertEquals(recalled.getLocalId(), merged.getLocalId());
        assertEquals(Long.valueOf(5000L), merged.getRecalledAt());
        assertNull(merged.getText());
        assertNull(merged.getAttachmentJson());
        assertEquals(0, matchCount("chuc"));
    }

    @Test
    public void deletingLatestUpdatesPreviewAndClearsFtsAndTranslation() throws Exception {
        seedConversation();
        db.messageDao().insertRespectingVisibleFrom(message(101, "Tin còn lại"), "me");
        db.messageDao().insertRespectingVisibleFrom(message(102, "Xin xóa"), "me");
        saveTranslation("client-102", "en", "Remove this");
        saveTranslation("client-102", "vi", "Xin xóa");
        saveTranslation("client-101", "en", "Remaining");
        assertEquals(1, matchCount("xin"));

        // Phòng chat lùi về tin mới nhất còn đọc được và chỉ xóa bản dịch của tin bị ẩn.
        db.messageDao().recordDeletionForMe(102L);
        db.messageDao().deleteByServerId(102L);
        db.translationDao().deleteAllForMessage("client-102");
        MessageEntity latest = db.messageDao().findLatestVisibleMessageSync("conv", "me");
        db.conversationDao().updateLastMessageMeta("conv", latest.getServerId(), latest.getCreatedAt());
        assertNull(db.messageDao().findByServerId(102L));
        assertTrue(db.messageDao().isDeletedForMe(102L));
        assertFalse(db.messageDao().insertRespectingVisibleFrom(message(102, "Xin xóa"), "me"));
        assertNull(db.translationDao().findSync("client-102", "en"));
        assertNull(db.translationDao().findSync("client-102", "vi"));
        assertEquals("Remaining", db.translationDao().findSync("client-101", "en").getTranslatedText());
        assertEquals(0, matchCount("xin"));
        ConversationListItem summary = awaitValue(db.conversationDao().observeConversationSummaries("me")).get(0);
        assertEquals("Tin còn lại", summary.lastMessageText);
        assertEquals(Long.valueOf(101L), summary.conversation.getLastMessageId());
    }

    @Test
    public void deletingBeforeWorkerAcknowledgmentClearsPendingRowByClientId() {
        MessageEntity pending = message(102, "Tin đang gửi");
        pending.setServerId(null);
        pending.setStatus("SENDING");
        db.messageDao().insert(pending);
        db.messageDao().recordDeletionForMe(102);
        assertTrue(db.messageDao().isDeletedForMe(102));
        db.messageDao().deletePendingByClientId("client-102");
        assertNull(db.messageDao().findByClientId("client-102"));
        assertFalse(db.messageDao().insertRespectingVisibleFrom(message(102, "Tin đang gửi"), "me"));
    }

    @Test
    public void reactionsUpsertReplaceObserveAndDeleteByIdentity() throws Exception {
        seedConversation();
        db.messageDao().insertRespectingVisibleFrom(message(101, "Tin có cảm xúc"), "me");
        db.reactionDao().upsert(new MessageReactionEntity(101, "me", "👍", 1000));
        db.reactionDao().upsert(new MessageReactionEntity(101, "me", "❤️", 2000));
        List<MessageReactionEntity> reactions = db.reactionDao().findByMessageSync(101L);
        assertEquals(1, reactions.size());
        assertEquals("❤️", reactions.get(0).getEmoji());
        db.reactionDao().replaceForMessage(101L, List.of(
                new MessageReactionEntity(101, "me", "❤️", 2000),
                new MessageReactionEntity(101, "peer", "😢", 3000)));
        assertEquals(2, awaitValue(db.reactionDao().observeByConversation("conv")).size());
        db.reactionDao().delete(101L, "me");
        assertEquals("peer", db.reactionDao().findByMessageSync(101L).get(0).getUserId());
        db.reactionDao().deleteByMessageServerId(101L);
        assertTrue(db.reactionDao().findByMessageSync(101L).isEmpty());
    }

    @Test
    public void hiddenPacksLeaveStickerCacheReadableAfterUninstall() throws Exception {
        StickerPackEntity pack = new StickerPackEntity();
        pack.setPackId("pack-1");
        pack.setCode("daily");
        pack.setName("Thường Ngày");
        pack.setThumbnailUrl("/api/stickers/assets/daily/hi.png");
        pack.setInstalled(true);
        db.stickerDao().upsertPacks(List.of(pack));
        StickerEntity sticker = new StickerEntity();
        sticker.setStickerId("sticker-1");
        sticker.setPackId("pack-1");
        sticker.setCode("hi");
        sticker.setLabel("Chào");
        sticker.setImageUrl("/api/stickers/assets/daily/hi.png");
        db.stickerDao().upsertStickers(List.of(sticker));
        db.stickerDao().updateStickerLocalPath("sticker-1", "stickers/daily/hi.png");
        assertEquals(1, awaitValue(db.stickerDao().observeInstalledStickers()).size());

        // Gỡ bộ khỏi khay không làm mất nhãn dán được dùng trong tin đã gửi.
        db.stickerDao().updatePackInstalled("pack-1", false);
        assertFalse(db.stickerDao().findPackByIdSync("pack-1").getInstalled());
        assertTrue(awaitValue(db.stickerDao().observeInstalledStickers()).isEmpty());
        assertEquals("stickers/daily/hi.png", db.stickerDao().findStickerByIdSync("sticker-1").getLocalPath());
        db.stickerDao().clearAll();
        assertTrue(db.stickerDao().findAllPacksSync().isEmpty());
        assertTrue(db.stickerDao().findStickersByPackSync("pack-1").isEmpty());
    }

    private void seedConversation() {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("conv");
        conversation.setType("GROUP");
        db.conversationDao().insert(conversation);
        ConversationMemberEntity member = new ConversationMemberEntity();
        member.setConversationId("conv");
        member.setUserId("me");
        member.setRole("MEMBER");
        db.conversationDao().insertMember(member);
    }

    private MessageEntity message(long serverId, String text) {
        MessageEntity message = new MessageEntity();
        message.setServerId(serverId);
        message.setClientId("client-" + serverId);
        message.setConversationId("conv");
        message.setSenderId("peer");
        message.setType("TEXT");
        message.setText(text);
        message.setCreatedAt(serverId);
        message.setStatus("SENT");
        return message;
    }

    private void saveTranslation(String key, String language, String text) {
        MessageTranslationEntity translation = new MessageTranslationEntity();
        translation.setMessageKey(key);
        translation.setTargetLang(language);
        translation.setTranslatedText(text);
        db.translationDao().insert(translation);
    }

    private int matchCount(String query) {
        try (Cursor rows = db.getOpenHelper().getReadableDatabase().query(
                "SELECT docid FROM message_fts WHERE message_fts MATCH ?", new Object[] { query })) {
            return rows.getCount();
        }
    }

    // LiveData chỉ cho gắn observer trên main, truy vấn Room vẫn chạy trên executor đĩa.
    private static <T> T awaitValue(LiveData<T> source) throws InterruptedException {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var value = new AtomicReference<T>();
        var ready = new CountDownLatch(1);
        Observer<T> observer = row -> {
            value.set(row);
            ready.countDown();
        };
        instrumentation.runOnMainSync(() -> source.observeForever(observer));
        try {
            assertTrue("Room chưa trả dữ liệu", ready.await(5, TimeUnit.SECONDS));
            return value.get();
        } finally {
            instrumentation.runOnMainSync(() -> source.removeObserver(observer));
        }
    }
}
