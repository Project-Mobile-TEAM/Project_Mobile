package com.utechat;

import android.os.SystemClock;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Kiểm tra back stack, tab và recreate trên Activity thật, không gọi đăng nhập giả. */
@RunWith(AndroidJUnit4.class)
public class FoundationNavigationTest {
    @Test public void previewTabsSurviveRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Chờ probe hoàn tất; nếu môi trường không có server, dùng lối ngoại tuyến.
            long deadline = SystemClock.elapsedRealtime() + 25000;
            AtomicBoolean auth = new AtomicBoolean();
            AtomicBoolean error = new AtomicBoolean();
            do {
                scenario.onActivity(activity -> {
                    NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                            .findFragmentById(R.id.nav_host_fragment);
                    auth.set(host.getNavController().getCurrentDestination().getId() == R.id.loginFragment);
                    android.view.View retry = activity.findViewById(R.id.btn_retry);
                    error.set(retry != null && retry.getVisibility() == android.view.View.VISIBLE);
                });
                if (auth.get() || error.get()) break;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            if (!auth.get() && error.get()) onView(withId(R.id.btn_continue_offline)).perform(click());
            onView(withId(R.id.btn_preview)).perform(click());
            onView(withId(R.id.bottom_nav)).check(matches(isDisplayed()));
            onView(withId(R.id.nav_contacts)).perform(click());
            onView(withText(R.string.title_contacts)).check(matches(isDisplayed()));
            scenario.recreate(); // Giả lập xoay màn hình để kiểm navigation state đã lưu.
            onView(withText(R.string.title_contacts)).check(matches(isDisplayed()));
            onView(withId(R.id.nav_profile)).perform(click());
            onView(withText(R.string.title_profile)).check(matches(isDisplayed()));
            onView(withId(R.id.nav_chat)).perform(click());
            onView(withText(R.string.title_chats)).check(matches(isDisplayed()));
        }
    }
}
