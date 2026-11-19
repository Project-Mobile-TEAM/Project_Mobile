package com.utechat.di;

import android.content.Context;
import com.utechat.BuildConfig;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.remote.api.*;
import com.utechat.data.session.SessionManager;
import com.utechat.data.repository.*;
import com.utechat.data.repository.fake.*;
import com.utechat.data.repository.real.*;

/** Service Locator thuần Java: một nơi ghép phụ thuộc theo slide, không dùng Hilt/Dagger. */
public final class Injection {
    private static AuthRepository auth;
    private static ContactsRepository contacts;
    private static ChatRepository chat;
    private static HealthRepository health;
    private static final java.util.concurrent.Executor DISK = java.util.concurrent.Executors.newSingleThreadExecutor();
    // Mạng danh bạ có hàng đợi riêng, không giữ luồng DISK khi đang chờ HTTP.
    private static final java.util.concurrent.Executor CONTACT_NETWORK = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Injection() { }

    public static synchronized AuthRepository provideAuthRepository(Context context) {
        if (auth == null) {
            AppDatabase db = AppDatabase.getInstance(context);
            // Đăng xuất xoá cache Room trên luồng đĩa riêng (không truy cập DAO trên luồng chính).
            auth = useFake() ? new FakeAuthRepository() : new RealAuthRepository(ApiClient.getRetrofit().create(AuthApi.class),
                    SessionManager.get(), db.userDao(), DISK, db::clearAllTables);
        }
        return auth;
    }
    public static synchronized ContactsRepository provideContactsRepository(Context context) {
        if (contacts == null) {
            if (useFake()) {
                FakeContactsRepository fake = new FakeContactsRepository(provideContactsSession(context));
                contacts = fake;
                // Xóa các LiveData cache cũ ngay khi Fake đăng xuất hoặc đăng nhập lại cùng tài khoản.
                provideAuthRepository(context).getCurrentUser().observeForever(user -> fake.onSessionChanged());
            }
            else {
                AppDatabase db = AppDatabase.getInstance(context);
                contacts = new RealContactsRepository(db.contactDao(), db.userDao(),
                        ApiClient.getRetrofit().create(ContactApi.class), CONTACT_NETWORK, DISK,
                        provideContactsSession(context), db::runInTransaction);
            }
        }
        return contacts;
    }
    /** Kiểm phiên và ghi cache dưới cùng khóa với SessionManager.clear để không có khoảng đua. */
    private static CurrentUserIdProvider provideContactsSession(Context context) {
        // Khởi tạo Auth trước để gắn dọn cache vào SessionManager trước mọi thao tác danh bạ.
        AuthRepository repository = provideAuthRepository(context);
        return useFake() ? (FakeAuthRepository) repository : SessionManager.get();
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
