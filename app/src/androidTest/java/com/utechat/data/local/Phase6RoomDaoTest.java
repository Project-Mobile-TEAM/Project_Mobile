package com.utechat.data.local;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageTranslationEntity;
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
 * Kiểm thử trên SQLite thực tế (Room in-memory) các truy vấn DAO mở rộng của Phase 6 (Plan 06-03):
 * - TransferDao: findByClientIdSync, observeAll, updateProgress, updateState, markUploaded, deleteByClientId.
 * - TranslationDao: findSync, observeByConversation, deleteByKey, deleteOrphaned.
 */
@RunWith(AndroidJUnit4.class)
public class Phase6RoomDaoTest {
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
    public void transferDaoLifecycleProgressMarkUploadedAndDelete() throws Exception {
        AttachmentTransferEntity entity = new AttachmentTransferEntity();
        entity.setClientId("client-img-1");
        entity.setLocalUri("content://com.utechat.fileprovider/pending_media/img1.jpg");
        entity.setState("PENDING");
        entity.setProgress(0);
        entity.setDirection("UPLOAD");
        entity.setLocalPath("/data/user/0/com.utechat/files/pending_media/img1.jpg");
        entity.setKind("IMAGE");
        entity.setMime("image/jpeg");
        entity.setSizeBytes(120_000L);
        entity.setFileName("anh_demo.jpg");
        entity.setWidth(1280);
        entity.setHeight(720);
        entity.setUpdatedAt(1000L);

        db.transferDao().insert(entity);

        AttachmentTransferEntity found = db.transferDao().findByClientIdSync("client-img-1");
        assertNotNull(found);
        assertEquals("PENDING", found.getState());
        assertEquals("UPLOAD", found.getDirection());
        assertEquals("IMAGE", found.getKind());
        assertEquals(Long.valueOf(120_000L), found.getSizeBytes());

        List<AttachmentTransferEntity> all = getOrAwaitValue(db.transferDao().observeAll());
        assertEquals(1, all.size());

        // Cập nhật tiến độ đang tải lên.
        db.transferDao().updateProgress("client-img-1", "UPLOADING", 60, 2000L);
        AttachmentTransferEntity uploading = db.transferDao().findByClientIdSync("client-img-1");
        assertEquals("UPLOADING", uploading.getState());
        assertEquals(60, uploading.getProgress());
        assertEquals(2000L, uploading.getUpdatedAt());

        // Ghi lỗi tạm rồi gọi markUploaded -> phải xóa errorCode và giữ lại metadata khi tham số null.
        db.transferDao().updateState("client-img-1", "FAILED", 60, "NETWORK", 2500L);
        AttachmentTransferEntity failed = db.transferDao().findByClientIdSync("client-img-1");
        assertEquals("FAILED", failed.getState());
        assertEquals("NETWORK", failed.getErrorCode());

        db.transferDao().markUploaded(
                "client-img-1",
                "/api/media/11111111-1111-1111-1111-111111111111",
                "image/webp",
                98_500L,
                null,
                null,
                null,
                null,
                3000L);

        AttachmentTransferEntity uploaded = db.transferDao().findByClientIdSync("client-img-1");
        assertEquals("UPLOADED", uploaded.getState());
        assertEquals(100, uploaded.getProgress());
        assertEquals("/api/media/11111111-1111-1111-1111-111111111111", uploaded.getMediaUrl());
        assertEquals("image/webp", uploaded.getMime());
        assertEquals(Long.valueOf(98_500L), uploaded.getSizeBytes());
        // Các cột truyền null vào COALESCE phải giữ nguyên giá trị cũ đã lưu.
        assertEquals("anh_demo.jpg", uploaded.getFileName());
        assertEquals(Integer.valueOf(1280), uploaded.getWidth());
        assertEquals(Integer.valueOf(720), uploaded.getHeight());
        assertNull(uploaded.getErrorCode());
        assertEquals(3000L, uploaded.getUpdatedAt());

        // Xóa theo clientId.
        db.transferDao().deleteByClientId("client-img-1");
        assertNull(db.transferDao().findByClientIdSync("client-img-1"));
    }

    @Test
    public void translationDaoFindSyncObserveByConversationAndDeleteOrphaned() throws Exception {
        ConversationEntity conv1 = new ConversationEntity();
        conv1.setId("conv-1");
        conv1.setType("DIRECT");
        conv1.setLastMessageAt(1000L);
        db.conversationDao().insert(conv1);

        ConversationEntity conv2 = new ConversationEntity();
        conv2.setId("conv-2");
        conv2.setType("DIRECT");
        conv2.setLastMessageAt(2000L);
        db.conversationDao().insert(conv2);

        db.messageDao().insert(message("msg-1", "conv-1", "Xin chào"));
        db.messageDao().insert(message("msg-2", "conv-2", "Hello world"));

        // Bản dịch thuộc conv-1, bản dịch thuộc conv-2 và bản dịch mồ côi không còn tin nhắn gốc.
        db.translationDao().insert(translation("msg-1", "en", "Hello"));
        db.translationDao().insert(translation("msg-2", "vi", "Xin chào thế giới"));
        db.translationDao().insert(translation("msg-deleted-orphan", "en", "Ghost text"));

        MessageTranslationEntity syncHit = db.translationDao().findSync("msg-1", "en");
        assertNotNull(syncHit);
        assertEquals("Hello", syncHit.getTranslatedText());
        assertNull(db.translationDao().findSync("msg-1", "vi"));

        // observeByConversation chỉ trả bản dịch của tin nhắn thuộc conv-1.
        List<MessageTranslationEntity> conv1Translations = getOrAwaitValue(
                db.translationDao().observeByConversation("conv-1"));
        assertEquals(1, conv1Translations.size());
        assertEquals("msg-1", conv1Translations.get(0).getMessageKey());
        assertEquals("Hello", conv1Translations.get(0).getTranslatedText());

        // deleteOrphaned xóa bản dịch mồ côi nhưng giữ nguyên bản dịch có tin nhắn gốc.
        db.translationDao().deleteOrphaned();
        assertNull(db.translationDao().findSync("msg-deleted-orphan", "en"));
        assertNotNull(db.translationDao().findSync("msg-1", "en"));
        assertNotNull(db.translationDao().findSync("msg-2", "vi"));

        // deleteByKey xóa đúng bản dịch mục tiêu.
        db.translationDao().deleteByKey("msg-1", "en");
        assertNull(db.translationDao().findSync("msg-1", "en"));
        assertNotNull(db.translationDao().findSync("msg-2", "vi"));
    }

    private static MessageEntity message(String clientId, String conversationId, String text) {
        MessageEntity msg = new MessageEntity();
        msg.setClientId(clientId);
        msg.setConversationId(conversationId);
        msg.setSenderId("u-sender");
        msg.setType("TEXT");
        msg.setText(text);
        msg.setCreatedAt(1000L);
        msg.setStatus("SENT");
        return msg;
    }

    private static MessageTranslationEntity translation(String key, String targetLang, String translatedText) {
        MessageTranslationEntity entity = new MessageTranslationEntity();
        entity.setMessageKey(key);
        entity.setTargetLang(targetLang);
        entity.setTranslatedText(translatedText);
        return entity;
    }

    /** Chờ lấy giá trị đầu tiên của LiveData trên luồng chính Android. */
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
