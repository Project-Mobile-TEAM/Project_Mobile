package com.utechat.di;

import android.content.Context;
import com.utechat.BuildConfig;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.remote.api.*;
import com.utechat.data.repository.*;
import com.utechat.data.repository.fake.*;
import com.utechat.data.repository.real.*;

/** Service Locator thuần Java: một nơi ghép phụ thuộc theo slide, không dùng Hilt/Dagger. */
public final class Injection {
    private static AuthRepository auth;
    private static ContactsRepository contacts;
    private static ChatRepository chat;
    private static HealthRepository health;
    private Injection() { }

    public static synchronized AuthRepository provideAuthRepository(Context context) {
        if (auth == null) {
            auth = useFake() ? new FakeAuthRepository() : new RealAuthRepository(
                    AppDatabase.getInstance(context).userDao(), ApiClient.getRetrofit().create(AuthApi.class));
        }
        return auth;
    }
    public static synchronized ContactsRepository provideContactsRepository(Context context) {
        if (contacts == null) {
            if (useFake()) contacts = new FakeContactsRepository();
            else {
                AppDatabase db = AppDatabase.getInstance(context);
                contacts = new RealContactsRepository(db.contactDao(), db.userDao(),
                        ApiClient.getRetrofit().create(ContactApi.class));
            }
        }
        return contacts;
    }
    public static synchronized ChatRepository provideChatRepository(Context context) {
        if (chat == null) {
            if (useFake()) chat = new FakeChatRepository();
            else {
                AppDatabase db = AppDatabase.getInstance(context);
                chat = new RealChatRepository(db.conversationDao(), db.messageDao(),
                        ApiClient.getRetrofit().create(ChatApi.class));
            }
        }
        return chat;
    }
    public static synchronized HealthRepository provideHealthRepository() {
        // Luôn dùng API thật; bật Fake không được biến probe thành CONNECTED giả.
        if (health == null) health = new HealthRepository(ApiClient.getRetrofit().create(ApiService.class));
        return health;
    }
    private static boolean useFake() {
        // Chặn Fake thêm lần nữa ở runtime nếu sau này sửa nhầm buildConfig của Release.
        return BuildConfig.DEBUG && BuildConfig.USE_FAKE_DATA;
    }
}
