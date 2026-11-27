package com.utechat.ui.chat;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.os.Bundle;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.MainActivity;
import com.utechat.R;
import com.utechat.data.repository.fake.DemoData;
import com.utechat.data.repository.fake.FakeChatRepository;
import com.utechat.data.repository.fake.FakeStickerRepository;
import com.utechat.data.session.SessionManager;
import com.utechat.di.Injection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Chạy luồng menu thật trên ActivityScenario, với dữ liệu hội thoại và nhãn dán chạy cục bộ. */
@RunWith(AndroidJUnit4.class)
public class Phase7MessageActionsUiTest {
    // Nội dung người dùng do fixture tạo. Nhãn giao diện được kiểm tra theo mã tài nguyên.
    private static final String SAMPLE_TEXT = "phase7-ui-fixture";
    private static final String FIRST_STICKER_LABEL = "Xin chào";
    private static final String CAMPUS_PACK_NAME = "UTE Học Đường";

    @Before
    public void setUp() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        context.getSharedPreferences("utechat_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("notif_prompted_once", true).commit();
        instrumentation.runOnMainSync(() -> {
            Injection.resetForTest();
            SessionManager.get().clear();
            SessionManager.get().saveAuthenticated("phase7-ui-test-token", DemoData.ALICE, true);
            FakeChatRepository chat = new FakeChatRepository();
            chat.enqueueTextMessage(DemoData.DIRECT, SAMPLE_TEXT);
            Injection.overrideChatRepositoryForTest(chat);
            Injection.overrideStickerRepositoryForTest(new FakeStickerRepository());
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
    public void longPressShowsActionsAndReactionDetails() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openRoom(scenario);
            onView(withText(SAMPLE_TEXT)).perform(longClick());
            for (int id : new int[]{R.id.emoji_0, R.id.emoji_1, R.id.emoji_2,
                    R.id.emoji_3, R.id.emoji_4, R.id.emoji_5}) {
                onView(withId(id)).check(matches(isDisplayed()));
            }
            onView(withText(R.string.chat_action_recall)).check(matches(isDisplayed()));
            onView(withText(R.string.chat_action_delete_for_me)).check(matches(isDisplayed()));
            onView(withId(R.id.emoji_0)).perform(click());

            // Phần chi tiết cảm xúc ràng buộc vào đúng hàng tin thay vì nội dung dịch.
            onView(allOf(withId(R.id.reactions_container), isDisplayed())).perform(click());
            onView(withText(R.string.chat_reactions_title)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void senderCanRecallOwnMessage() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openRoom(scenario);
            onView(withText(SAMPLE_TEXT)).perform(longClick());
            onView(withText(R.string.chat_action_recall)).perform(click());
            onView(withText(R.string.chat_recall_confirm_title)).check(matches(isDisplayed()));
            onView(withId(android.R.id.button1)).perform(click());
            onView(withText(R.string.chat_message_recalled)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void stickerTraySendsStickerAndExposesPackStore() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openRoom(scenario);
            onView(withId(R.id.input)).check(matches(isDisplayed()));
            onView(withId(R.id.sticker)).perform(click());
            onView(withId(R.id.sticker_grid)).check(matches(isDisplayed()));
            onView(allOf(withId(R.id.sticker_thumbnail),
                    androidx.test.espresso.matcher.ViewMatchers.withContentDescription(FIRST_STICKER_LABEL)))
                    .perform(click());
            onView(withId(R.id.sticker_image)).check(matches(isDisplayed()));

            // Sau khi gửi, khay đóng. Mở lại để kiểm tra nút và danh mục cửa hàng.
            onView(withId(R.id.sticker)).perform(click());
            onView(withId(R.id.sticker_store_open)).perform(click());
            onView(withText(R.string.sticker_store_title)).check(matches(isDisplayed()));
            onView(withText(R.string.sticker_store_install)).perform(click());
            onView(allOf(withText(R.string.sticker_store_uninstall),
                    hasSibling(hasDescendant(withText(CAMPUS_PACK_NAME))))).perform(click());
            onView(withText(R.string.sticker_store_install)).check(matches(isDisplayed()));
        }
    }

    private static void openRoom(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                    .findFragmentById(R.id.nav_host_fragment);
            assertNotNull(host);
            NavController nav = host.getNavController();
            if (nav.getCurrentDestination() != null
                    && nav.getCurrentDestination().getId() == R.id.splashFragment) {
                nav.navigate(R.id.nav_chat, null,
                        new NavOptions.Builder().setPopUpTo(R.id.splashFragment, true).build());
            }
            Bundle args = new Bundle();
            args.putString("conversationId", DemoData.DIRECT);
            args.putLong("targetMessageLocalId", -1L);
            nav.navigate(R.id.chatRoomFragment, args);
        });
    }
}
