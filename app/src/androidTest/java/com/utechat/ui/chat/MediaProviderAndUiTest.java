package com.utechat.ui.chat;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.lifecycle.MutableLiveData;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.MainActivity;
import com.utechat.R;
import com.utechat.data.MainThreadExecutor;
import com.utechat.data.call.CallService;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.media.MediaDownloadManager;
import com.utechat.data.notifications.NotificationHelper;
import com.utechat.data.repository.fake.DemoData;
import com.utechat.data.repository.fake.FakeChatRepository;
import com.utechat.data.session.SessionManager;
import com.utechat.data.translate.FakeMessageTranslator;
import com.utechat.data.translate.MessageTranslationManager;
import com.utechat.di.Injection;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Kiểm thử instrumentation Phase 6 trên Activity thật (MainActivity + ActivityScenario, KHÔNG dùng FragmentScenario):
 * 1) FileProvider tạo URI content:// hợp lệ cho media_cache/ và pending_media/ (không chứa JWT).
 * 2) Chạm thông báo cũ của tài khoản A sau khi đăng nhập tài khoản B, hoặc hội thoại đã bị xóa thành viên:
 *    không điều hướng sai và không khởi chạy dịch vụ nền microphone CallService (T-06-24).
 * 3) Dựng đầy đủ các bong bóng media/vị trí/cuộc gọi + dịch nội tuyến EN <-> VI và ẩn bản dịch bằng @StringRes (D-03, D-13..D-15).
 */
@RunWith(AndroidJUnit4.class)
public class MediaProviderAndUiTest {

    private Context targetContext;

    @Before
    public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Injection.resetForTest();
            SessionManager.get().clear();
        });
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Injection.resetForTest();
            SessionManager.get().clear();
        });
    }

    @Test
    public void fileProviderGeneratesContentUriForCacheAndPendingMedia() throws Exception {
        File cacheDir = new File(targetContext.getCacheDir(), "media_cache");
        File pendingDir = new File(targetContext.getFilesDir(), "pending_media");
        assertTrue(cacheDir.isDirectory() || cacheDir.mkdirs());
        assertTrue(pendingDir.isDirectory() || pendingDir.mkdirs());

        File cachedPdf = new File(cacheDir, "test_doc_phase6.pdf");
        File pendingJpg = new File(pendingDir, "test_photo_phase6.jpg");
        try {
            writeBytes(cachedPdf, "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
            writeBytes(pendingJpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

            String authority = targetContext.getPackageName() + ".fileprovider";
            Uri cacheUri = FileProvider.getUriForFile(targetContext, authority, cachedPdf);
            Uri pendingUri = FileProvider.getUriForFile(targetContext, authority, pendingJpg);

            assertEquals("content", cacheUri.getScheme());
            assertEquals("content", pendingUri.getScheme());
            assertTrue(cacheUri.toString().startsWith("content://com.utechat.fileprovider/"));
            assertTrue(pendingUri.toString().startsWith("content://com.utechat.fileprovider/"));
            assertFalse("URI không được chứa JWT", cacheUri.toString().contains("Bearer"));
            assertFalse("URI không được chứa JWT", pendingUri.toString().contains("Bearer"));

            Intent externalIntent = MediaDownloadManager.buildExternalViewIntent(
                    targetContext, cachedPdf, "application/pdf");
            assertEquals(Intent.ACTION_VIEW, externalIntent.getAction());
            assertEquals(cacheUri, externalIntent.getData());
            assertEquals("application/pdf", externalIntent.getType());
            assertTrue((externalIntent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        } finally {
            // Dọn sạch tệp kiểm thử sau khi xác minh.
            cachedPdf.delete();
            pendingJpg.delete();
        }
    }

    @Test
    public void staleAccountAndRemovedConversationNotificationTapsLeaveDestinationAndCallServiceUnchanged()
            throws Exception {
        // Phiên tài khoản A tạo ra sessionKey cũ cho thông báo.
        final String[] sessionKeyA = new String[1];
        final String[] sessionKeyB = new String[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SessionManager.get().saveAuthenticated("jwt-account-a", DemoData.ALICE, true);
            sessionKeyA[0] = SessionManager.get().sessionKey();
            // Chuyển sang tài khoản B: mọi thông báo mang sessionKey của A đều là cũ.
            SessionManager.get().saveAuthenticated("jwt-account-b", DemoData.BOB, true);
            sessionKeyB[0] = SessionManager.get().sessionKey();
            Injection.overrideChatRepositoryForTest(new FakeChatRepository());
        });
        assertNotNull(sessionKeyA[0]);
        assertNotNull(sessionKeyB[0]);

        // Ghi vào Room một hội thoại nơi tài khoản B đã bị xóa (left = true).
        String removedConvId = "conv-removed-phase6-test";
        CountDownLatch dbReady = new CountDownLatch(1);
        Injection.getDiskExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(targetContext);
            ConversationEntity conv = new ConversationEntity();
            conv.setId(removedConvId);
            conv.setType("GROUP");
            conv.setTitle("Nhóm đã rời");
            db.conversationDao().insert(conv);

            ConversationMemberEntity removed = new ConversationMemberEntity();
            removed.setConversationId(removedConvId);
            removed.setUserId(DemoData.BOB);
            removed.setRole("MEMBER");
            removed.setLeft(true);
            db.conversationDao().insertMember(removed);
            dbReady.countDown();
        });
        assertTrue(dbReady.await(5, TimeUnit.SECONDS));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigateToChatList(scenario);
            assertEquals(R.id.chatListFragment, currentDestinationId(scenario));
            assertFalse("CallService chưa chạy", isCallServiceRunning(targetContext));

            // 1) Chạm thông báo cuộc gọi/tin nhắn cũ của tài khoản A khi đang đăng nhập B.
            Intent staleAccountIntent = new Intent(targetContext, MainActivity.class);
            staleAccountIntent.putExtra(NotificationHelper.EXTRA_OPEN_CALL_ID, "stale-call-account-a");
            staleAccountIntent.putExtra(NotificationHelper.EXTRA_NOTIFICATION_SESSION_KEY, sessionKeyA[0]);
            deliverNewIntent(scenario, staleAccountIntent);
            SystemClock.sleep(300);

            assertEquals("Thông báo tài khoản A không được điều hướng",
                    R.id.chatListFragment, currentDestinationId(scenario));
            assertFalse("Thông báo cũ không được khởi chạy CallService", isCallServiceRunning(targetContext));

            // 2) Chạm thông báo của tài khoản B nhưng nhắm vào hội thoại đã bị xóa thành viên (left = true).
            Intent removedMemberIntent = new Intent(targetContext, MainActivity.class);
            removedMemberIntent.putExtra(NotificationHelper.EXTRA_OPEN_CONVERSATION_ID, removedConvId);
            removedMemberIntent.putExtra(NotificationHelper.EXTRA_NOTIFICATION_SESSION_KEY, sessionKeyB[0]);
            deliverNewIntent(scenario, removedMemberIntent);

            // Chờ luồng DISK xác minh xong rồi xác nhận vẫn đứng ở danh sách chat.
            CountDownLatch diskFlushed = new CountDownLatch(1);
            Injection.getDiskExecutor().execute(diskFlushed::countDown);
            assertTrue(diskFlushed.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(300);

            assertEquals("Hội thoại đã rời không được mở từ thông báo",
                    R.id.chatListFragment, currentDestinationId(scenario));
            assertFalse("Không được khởi chạy CallService", isCallServiceRunning(targetContext));
        } finally {
            CountDownLatch cleaned = new CountDownLatch(1);
            Injection.getDiskExecutor().execute(() -> {
                AppDatabase.getInstance(targetContext).clearAllTables();
                cleaned.countDown();
            });
            cleaned.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void rendersMediaBubblesAndInlineTranslationShowHide() {
        SeededMediaChatRepository seededRepo = new SeededMediaChatRepository();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SessionManager.get().saveAuthenticated("jwt-ui-test", DemoData.ALICE, true);
            Injection.overrideChatRepositoryForTest(seededRepo);
            MessageTranslationManager fakeTranslationManager = new MessageTranslationManager(
                    AppDatabase.getInstance(targetContext).translationDao(),
                    new FakeMessageTranslator(),
                    () -> MessageTranslationManager.NetworkState.WIFI_UNMETERED,
                    SessionManager.get(),
                    Injection.getDiskExecutor(),
                    MainThreadExecutor.INSTANCE);
            Injection.overrideMessageTranslationManagerForTest(fakeTranslationManager);
        });

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigateToChatList(scenario);
            scenario.onActivity(activity -> {
                NavController nav = navControllerOf(activity);
                Bundle args = new Bundle();
                args.putString("conversationId", DemoData.DIRECT);
                args.putLong("targetMessageLocalId", -1L);
                nav.navigate(R.id.chatRoomFragment, args);
            });

            waitForMessageCount(scenario, 7);

            // Kiểm tra bong bóng media/vị trí/cuộc gọi dựng đúng dữ liệu (không vỡ layout ở cả EN và VI).
            scrollMessagesTo(scenario, 0);
            onView(withText("report_phase6.pdf")).check(matches(isDisplayed()));
            scrollMessagesTo(scenario, 4);
            onView(withText(containsString("10.8506, 106.7719"))).check(matches(isDisplayed()));
            scrollMessagesTo(scenario, 5);
            onView(withText(R.string.chat_type_call)).check(matches(isDisplayed()));

            // Tin nhắn TEXT ở cuối danh sách: nhấn giữ -> chọn "Dịch sang Tiếng Việt" / "Translate to Vietnamese".
            scrollMessagesTo(scenario, 6);
            onView(withText("Hello")).check(matches(isDisplayed()));
            onView(withText("Hello")).perform(longClick());
            onView(withText(R.string.chat_translate_to_vi)).inRoot(isDialog()).perform(click());

            // Xác minh khung dịch hiện đúng nhãn @StringRes, bản dịch và nút ẩn (tương thích cả EN lẫn VI).
            waitForViewDisplayed(R.string.chat_translated_label);
            onView(withText(R.string.chat_translated_label)).check(matches(isDisplayed()));
            onView(withText("Xin chào")).check(matches(isDisplayed()));
            onView(withText(R.string.chat_translation_hide)).check(matches(isDisplayed()));

            // Nhấn nút ẩn bản dịch -> khung translation_box ẩn đi (D-14).
            onView(allOf(withId(R.id.translation_hide), isDisplayed())).perform(click());
            onView(withId(R.id.translation_box))
                    .check(matches(not(isDisplayed())));
            onView(withId(R.id.translation_box))
                    .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
        }
    }

    private static void navigateToChatList(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            NavController nav = navControllerOf(activity);
            if (nav.getCurrentDestination() != null
                    && nav.getCurrentDestination().getId() == R.id.splashFragment) {
                nav.navigate(R.id.nav_chat, null,
                        new NavOptions.Builder().setPopUpTo(R.id.splashFragment, true).build());
            }
        });
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentDestinationId(scenario) == R.id.chatListFragment) {
                return;
            }
            SystemClock.sleep(50);
        }
        assertEquals(R.id.chatListFragment, currentDestinationId(scenario));
    }

    private static void deliverNewIntent(ActivityScenario<MainActivity> scenario, Intent intent) {
        scenario.onActivity(activity ->
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent));
    }

    private static int currentDestinationId(ActivityScenario<MainActivity> scenario) {
        AtomicInteger id = new AtomicInteger(-1);
        scenario.onActivity(activity -> {
            NavController nav = navControllerOf(activity);
            if (nav.getCurrentDestination() != null) {
                id.set(nav.getCurrentDestination().getId());
            }
        });
        return id.get();
    }

    private static NavController navControllerOf(MainActivity activity) {
        NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        assertNotNull(host);
        return host.getNavController();
    }

    private static void waitForMessageCount(ActivityScenario<MainActivity> scenario, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        AtomicInteger actual = new AtomicInteger(-1);
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> {
                RecyclerView rv = activity.findViewById(R.id.messages);
                if (rv != null && rv.getAdapter() != null) {
                    actual.set(rv.getAdapter().getItemCount());
                }
            });
            if (actual.get() == expected) {
                return;
            }
            SystemClock.sleep(50);
        }
        assertEquals(expected, actual.get());
    }

    private static void scrollMessagesTo(ActivityScenario<MainActivity> scenario, int position) {
        scenario.onActivity(activity -> {
            RecyclerView rv = activity.findViewById(R.id.messages);
            if (rv != null) {
                rv.scrollToPosition(position);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void waitForViewDisplayed(int stringResId) {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withText(stringResId)).check(matches(isDisplayed()));
                return;
            } catch (Throwable ignored) {
                SystemClock.sleep(50);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isCallServiceRunning(@NonNull Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningServiceInfo> running = am.getRunningServices(Integer.MAX_VALUE);
        if (running == null) return false;
        String target = CallService.class.getName();
        for (ActivityManager.RunningServiceInfo info : running) {
            if (target.equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    /** Repository mẫu nạp đủ 7 loại tin nhắn (FILE, IMAGE, VIDEO, AUDIO, LOCATION, CALL, TEXT) để kiểm giao diện. */
    private static final class SeededMediaChatRepository extends FakeChatRepository {
        private final MutableLiveData<List<MessageEntity>> seeded = new MutableLiveData<>();

        SeededMediaChatRepository() {
            super();
            long base = System.currentTimeMillis() - 60_000L;
            List<MessageEntity> list = new ArrayList<>();
            list.add(build(101L, "cid-file", DemoData.BOB, "FILE", null,
                    "{\"fileName\":\"report_phase6.pdf\",\"sizeBytes\":20480,\"mime\":\"application/pdf\"}", base));
            list.add(build(102L, "cid-img", DemoData.BOB, "IMAGE", "Ảnh mẫu",
                    "{\"fileName\":\"photo.jpg\",\"mime\":\"image/jpeg\"}", base + 1000L));
            list.add(build(103L, "cid-vid", DemoData.ALICE, "VIDEO", null,
                    "{\"fileName\":\"clip.mp4\",\"durationMs\":12000,\"mime\":\"video/mp4\"}", base + 2000L));
            list.add(build(104L, "cid-aud", DemoData.BOB, "AUDIO", null,
                    "{\"fileName\":\"voice.m4a\",\"durationMs\":5000,\"mime\":\"audio/mp4\"}", base + 3000L));
            list.add(build(105L, "cid-loc", DemoData.ALICE, "LOCATION", null,
                    "{\"latitude\":10.8506,\"longitude\":106.7719}", base + 4000L));
            list.add(build(106L, "cid-call", DemoData.BOB, "CALL", null,
                    "{\"call\":{\"state\":\"MISSED\"}}", base + 5000L));
            list.add(build(107L, "cid-txt", DemoData.BOB, "TEXT", "Hello", null, base + 6000L));
            seeded.setValue(list);
        }

        @Override
        public androidx.lifecycle.LiveData<List<MessageEntity>> observeVisibleMessages(
                String conversationId, int limit) {
            if (DemoData.DIRECT.equals(conversationId)) {
                return seeded;
            }
            return super.observeVisibleMessages(conversationId, limit);
        }

        private static MessageEntity build(
                long id, String clientId, String senderId, String type,
                String text, String attachmentJson, long createdAt) {
            MessageEntity m = new MessageEntity();
            m.setLocalId(id);
            m.setServerId(id);
            m.setClientId(clientId);
            m.setConversationId(DemoData.DIRECT);
            m.setSenderId(senderId);
            m.setType(type);
            m.setText(text);
            m.setAttachmentJson(attachmentJson);
            m.setCreatedAt(createdAt);
            m.setStatus("SENT");
            return m;
        }
    }
}
