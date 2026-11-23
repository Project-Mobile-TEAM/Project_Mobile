package com.utechat.data.translate;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.local.dao.TranslationDao;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.MessageTranslationEntity;
import com.utechat.data.session.SessionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Kiểm thử JVM đầy đủ cho Plan 06-07 (MessageTranslationManager — D-13..D-15, T-06-21..22):
 * 1. Từ chối SYSTEM, CALL, IMAGE/AUDIO/VIDEO/FILE/LOCATION hoặc text rỗng mà không gọi ML Kit.
 * 2. Cache hit trong Room trả kết quả 100% offline mà không chạm MessageTranslator.
 * 3. Mạng di động (CELLULAR_METERED) yêu cầu xác nhận NEEDS_CELLULAR_CONFIRMATION; sau xác nhận phát DOWNLOADING_MODEL -> TRANSLATED và lưu Room.
 * 4. Mạng Wi-Fi (WIFI_UNMETERED) tự tải ở trạng thái không xác định (không %), lưu Room và lần sau dùng offline.
 * 5. Lỗi OFFLINE / DOWNLOAD_FAILED giữ nguyên tin gốc, không làm bẩn cache và cho phép thử lại thành công khi có Wi-Fi.
 * 6. Đổi tài khoản/phiên giữa lúc đang tải mô hình -> bỏ kết quả trễ và tuyệt đối không ghi TranslationDao.
 */
public class MessageTranslationManagerTest {
    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private static final String SESSION_A = "1:u-alice";
    private static final String SESSION_B = "2:u-bob";

    private TranslationDao translationDao;
    private SessionManager session;
    private FakeMlKitTranslator fakeTranslator;
    private AtomicReference<MessageTranslationManager.NetworkState> networkState;
    private AtomicReference<String> activeSessionKey;
    private Map<String, MessageTranslationEntity> inMemoryCache;
    private MessageTranslationManager manager;

    @Before
    public void setUp() {
        translationDao = mock(TranslationDao.class);
        session = mock(SessionManager.class);
        fakeTranslator = new FakeMlKitTranslator();
        networkState = new AtomicReference<>(MessageTranslationManager.NetworkState.WIFI_UNMETERED);
        activeSessionKey = new AtomicReference<>(SESSION_A);
        inMemoryCache = new HashMap<>();

        when(session.sessionKey()).thenAnswer(inv -> activeSessionKey.get());
        when(session.runIfCurrent(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Runnable action = inv.getArgument(1);
            if (!key.equals(activeSessionKey.get())) {
                return false;
            }
            action.run();
            return true;
        });

        when(translationDao.findSync(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            return inMemoryCache.get(key);
        });
        doAnswer(inv -> {
            MessageTranslationEntity entity = inv.getArgument(0);
            inMemoryCache.put(entity.getMessageKey() + "|" + entity.getTargetLang(), entity);
            return null;
        }).when(translationDao).insert(any(MessageTranslationEntity.class));

        manager = new MessageTranslationManager(
                translationDao,
                fakeTranslator,
                networkState::get,
                session,
                Runnable::run,
                Runnable::run);
    }

    @Test
    public void rejectsSystemCallAndMediaMessagesWithoutCallingTranslator() {
        String[] unsupportedTypes = {"SYSTEM", "CALL", "IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION"};
        for (String type : unsupportedTypes) {
            List<MessageTranslationManager.TranslationUpdate> updates = new ArrayList<>();
            manager.translateMessage(msg("cid-" + type, type, "Caption"), "vi", true, updates::add);
            assertEquals(1, updates.size());
            assertEquals(MessageTranslationManager.TranslationStatus.ERROR, updates.get(0).status);
            assertEquals(MessageTranslationManager.ERR_UNSUPPORTED_TYPE, updates.get(0).errorCode);
        }

        // Tin TEXT nhưng rỗng/khoảng trắng hoặc ngôn ngữ không phải EN/VI cũng bị từ chối.
        List<MessageTranslationManager.TranslationUpdate> blankUpdates = new ArrayList<>();
        manager.translateMessage(msg("cid-blank", "TEXT", "   "), "vi", true, blankUpdates::add);
        assertEquals(MessageTranslationManager.ERR_UNSUPPORTED_TYPE, blankUpdates.get(0).errorCode);

        List<MessageTranslationManager.TranslationUpdate> langUpdates = new ArrayList<>();
        manager.translateMessage(msg("cid-fr", "TEXT", "Bonjour"), "fr", true, langUpdates::add);
        assertEquals(MessageTranslationManager.ERR_UNSUPPORTED_TYPE, langUpdates.get(0).errorCode);

        assertEquals(0, fakeTranslator.modelChecks);
        assertEquals(0, fakeTranslator.downloadCalls);
        assertEquals(0, fakeTranslator.translateCalls);
        verifyNoInteractions(translationDao);
    }

    @Test
    public void cacheHitReturnsSavedTranslationOfflineWithoutInvokingMlKit() {
        MessageTranslationEntity cached = new MessageTranslationEntity();
        cached.setMessageKey("cid-cached");
        cached.setTargetLang("vi");
        cached.setTranslatedText("Xin chào từ cache");
        inMemoryCache.put("cid-cached|vi", cached);

        // Đang OFFLINE hoàn toàn vẫn trả bản dịch từ Room mà không đụng ML Kit.
        networkState.set(MessageTranslationManager.NetworkState.OFFLINE);

        List<MessageTranslationManager.TranslationUpdate> updates = new ArrayList<>();
        manager.translateMessage(msg("cid-cached", "TEXT", "Hello from cache"), "vi", false, updates::add);

        assertEquals(1, updates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.TRANSLATED, updates.get(0).status);
        assertEquals("Xin chào từ cache", updates.get(0).translatedText);
        assertEquals("vi", updates.get(0).targetLang);
        assertEquals(0, fakeTranslator.modelChecks);
        assertEquals(0, fakeTranslator.downloadCalls);
        assertEquals(0, fakeTranslator.translateCalls);
    }

    @Test
    public void cellularPromptsConfirmationBeforeDownloadingAndDownloadsAfterConfirm() {
        networkState.set(MessageTranslationManager.NetworkState.CELLULAR_METERED);
        MessageEntity message = msg("cid-cell", "TEXT", "Good morning");

        // Lần 1 (chưa xác nhận 4G) -> trả NEEDS_CELLULAR_CONFIRMATION, tuyệt đối chưa tải mô hình.
        List<MessageTranslationManager.TranslationUpdate> promptUpdates = new ArrayList<>();
        manager.translateMessage(message, "vi", false, promptUpdates::add);

        assertEquals(1, promptUpdates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.NEEDS_CELLULAR_CONFIRMATION,
                promptUpdates.get(0).status);
        assertEquals(0, fakeTranslator.downloadCalls);
        assertTrue(inMemoryCache.isEmpty());

        // Lần 2 (sau khi người dùng bấm đồng ý tải qua 4G) -> phát DOWNLOADING_MODEL -> TRANSLATED và lưu Room.
        List<MessageTranslationManager.TranslationUpdate> confirmUpdates = new ArrayList<>();
        manager.translateMessage(message, "vi", true, confirmUpdates::add);

        assertEquals(2, confirmUpdates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.DOWNLOADING_MODEL, confirmUpdates.get(0).status);
        assertEquals(MessageTranslationManager.TranslationStatus.TRANSLATED, confirmUpdates.get(1).status);
        assertEquals("[vi] Good morning", confirmUpdates.get(1).translatedText);
        assertTrue("Phải truyền allowCellular=true khi tải sau xác nhận 4G", fakeTranslator.lastAllowCellular);
        assertEquals(2, fakeTranslator.downloadCalls); // tải đủ cả "en" và "vi"
        assertNotNull(inMemoryCache.get("cid-cell|vi"));
        verify(translationDao, times(1)).insert(any(MessageTranslationEntity.class));
    }

    @Test
    public void wifiAutoDownloadsWithIndeterminateStateAndSavesRoomCache() {
        networkState.set(MessageTranslationManager.NetworkState.WIFI_UNMETERED);
        MessageEntity message = msg("cid-wifi", "TEXT", "Xin chào");

        List<MessageTranslationManager.TranslationUpdate> firstRun = new ArrayList<>();
        manager.translateMessage(message, "en", false, firstRun::add);

        // Trên Wi-Fi tự tải ngay (không hỏi 4G), phát DOWNLOADING_MODEL (không có %) rồi TRANSLATED.
        assertEquals(2, firstRun.size());
        assertEquals(MessageTranslationManager.TranslationStatus.DOWNLOADING_MODEL, firstRun.get(0).status);
        assertEquals(MessageTranslationManager.TranslationStatus.TRANSLATED, firstRun.get(1).status);
        assertEquals("[en] Xin chào", firstRun.get(1).translatedText);
        assertFalse("Trên Wi-Fi phải khóa allowCellular=false", fakeTranslator.lastAllowCellular);
        assertEquals(2, fakeTranslator.downloadCalls);

        // Lần thứ 2 ở chế độ OFFLINE -> đọc thẳng cache Room, không gọi thêm ML Kit.
        networkState.set(MessageTranslationManager.NetworkState.OFFLINE);
        List<MessageTranslationManager.TranslationUpdate> secondRun = new ArrayList<>();
        manager.translateMessage(message, "en", false, secondRun::add);
        assertEquals(1, secondRun.size());
        assertEquals(MessageTranslationManager.TranslationStatus.TRANSLATED, secondRun.get(0).status);
        assertEquals("[en] Xin chào", secondRun.get(0).translatedText);
        assertEquals(1, fakeTranslator.translateCalls);
    }

    @Test
    public void offlineOrDownloadFailurePreservesOriginalAndAllowsRetry() {
        MessageEntity message = msg("cid-retry", "TEXT", "See you soon");

        // 1) Mất mạng khi chưa có mô hình -> ERROR(OFFLINE), không ghi bẩn Room.
        networkState.set(MessageTranslationManager.NetworkState.OFFLINE);
        List<MessageTranslationManager.TranslationUpdate> offlineUpdates = new ArrayList<>();
        manager.translateMessage(message, "vi", false, offlineUpdates::add);
        assertEquals(1, offlineUpdates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.ERROR, offlineUpdates.get(0).status);
        assertEquals(MessageTranslationManager.ERR_OFFLINE, offlineUpdates.get(0).errorCode);
        assertTrue(inMemoryCache.isEmpty());

        // 2) Có Wi-Fi nhưng tải mô hình thất bại -> DOWNLOADING_MODEL rồi ERROR(DOWNLOAD_FAILED), Room vẫn sạch.
        networkState.set(MessageTranslationManager.NetworkState.WIFI_UNMETERED);
        fakeTranslator.failNextDownload = true;
        List<MessageTranslationManager.TranslationUpdate> failUpdates = new ArrayList<>();
        manager.translateMessage(message, "vi", false, failUpdates::add);
        assertEquals(2, failUpdates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.DOWNLOADING_MODEL, failUpdates.get(0).status);
        assertEquals(MessageTranslationManager.TranslationStatus.ERROR, failUpdates.get(1).status);
        assertEquals(MessageTranslationManager.ERR_DOWNLOAD_FAILED, failUpdates.get(1).errorCode);
        assertTrue(inMemoryCache.isEmpty());

        // 3) Thử lại khi tải thành công -> DOWNLOADING_MODEL rồi TRANSLATED và lưu cache.
        fakeTranslator.failNextDownload = false;
        List<MessageTranslationManager.TranslationUpdate> retryUpdates = new ArrayList<>();
        manager.translateMessage(message, "vi", false, retryUpdates::add);
        assertEquals(2, retryUpdates.size());
        assertEquals(MessageTranslationManager.TranslationStatus.DOWNLOADING_MODEL, retryUpdates.get(0).status);
        assertEquals(MessageTranslationManager.TranslationStatus.TRANSLATED, retryUpdates.get(1).status);
        assertEquals("[vi] See you soon", retryUpdates.get(1).translatedText);
        assertNotNull(inMemoryCache.get("cid-retry|vi"));
    }

    @Test
    public void ignoresDelayedResultAfterSessionSwitch() {
        networkState.set(MessageTranslationManager.NetworkState.WIFI_UNMETERED);
        fakeTranslator.downloadedModels.add("en");
        fakeTranslator.downloadedModels.add("vi");
        // Giả lập đổi sang tài khoản B ngay trong lúc ML Kit đang dịch bất đồng bộ.
        fakeTranslator.onTranslateSideEffect = () -> activeSessionKey.set(SESSION_B);

        List<MessageTranslationManager.TranslationUpdate> updates = new ArrayList<>();
        manager.translateMessage(msg("cid-switch", "TEXT", "Secret text"), "vi", false, updates::add);

        assertTrue("Không được phát kết quả dịch của tài khoản cũ cho tài khoản mới", updates.isEmpty());
        assertTrue("Tuyệt đối không được ghi cache Room sau khi đổi phiên", inMemoryCache.isEmpty());
        verify(translationDao, never()).insert(any(MessageTranslationEntity.class));
    }

    // ======================== HELPERS & FAKE ========================

    private static MessageEntity msg(String clientId, String type, String text) {
        MessageEntity e = new MessageEntity();
        e.setClientId(clientId);
        e.setConversationId("conv-1");
        e.setSenderId("u-bob");
        e.setType(type);
        e.setText(text);
        e.setStatus("SENT");
        return e;
    }

    private static final class FakeMlKitTranslator implements MessageTranslator {
        final Set<String> downloadedModels = new HashSet<>();
        int modelChecks = 0;
        int downloadCalls = 0;
        int translateCalls = 0;
        boolean lastAllowCellular = false;
        boolean failNextDownload = false;
        Runnable onTranslateSideEffect;

        @Override
        public void isModelDownloaded(String langCode, ModelCheckCallback cb) {
            modelChecks++;
            cb.onResult(downloadedModels.contains(langCode));
        }

        @Override
        public void downloadModel(String langCode, boolean allowCellular, DownloadCallback cb) {
            downloadCalls++;
            lastAllowCellular = allowCellular;
            if (failNextDownload) {
                cb.onError("simulated network error");
                return;
            }
            downloadedModels.add(langCode);
            cb.onSuccess();
        }

        @Override
        public void translate(String sourceLang, String targetLang, String text, TranslateCallback cb) {
            translateCalls++;
            if (onTranslateSideEffect != null) {
                onTranslateSideEffect.run();
            }
            cb.onSuccess("[" + targetLang + "] " + text);
        }
    }
}
