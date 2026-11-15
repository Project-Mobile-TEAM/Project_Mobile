package com.utechat.ui.profile;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import java.util.Locale;

/**
 * Cài đặt người dùng của app (ADR-021, slide Session 4: SharedPreferences cho cài đặt nhỏ).
 * Ngôn ngữ do AppCompat tự lưu (AppLocalesMetadataHolderService); giao diện sáng/tối và thông báo lưu ở đây.
 */
public final class AppSettings {
    private static final String FILE = "settings";
    private static final String KEY_THEME = "theme";
    private static final String KEY_NOTIFICATIONS = "notifications";
    private AppSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Chế độ giao diện: MODE_NIGHT_NO (sáng), MODE_NIGHT_YES (tối) hoặc MODE_NIGHT_FOLLOW_SYSTEM (mặc định). */
    public static int getTheme(Context context) {
        return prefs(context).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Lưu và áp dụng ngay chế độ giao diện; Activity tự tạo lại để đổi màu. */
    public static void setTheme(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /** Gọi khi khởi động Activity: DayNight không tự nhớ lựa chọn của người dùng giữa các lần mở app. */
    public static void applySavedTheme(Context context) {
        AppCompatDelegate.setDefaultNightMode(getTheme(context));
    }

    public static boolean isNotificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS, true);
    }

    public static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply();
    }

    /** Mã ngôn ngữ đang dùng: "vi" hoặc "en". Chưa chọn thì theo ngôn ngữ máy (không phải vi thì coi là en). */
    public static String getLanguage() {
        LocaleListCompat chosen = AppCompatDelegate.getApplicationLocales();
        Locale locale = chosen.isEmpty() ? Locale.getDefault() : chosen.get(0);
        return locale != null && "vi".equals(locale.getLanguage()) ? "vi" : "en";
    }

    /** Đổi ngôn ngữ app; AppCompat tạo lại Activity và ghi nhớ lựa chọn. */
    public static void setLanguage(String languageTag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag));
    }
}
