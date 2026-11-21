package com.utechat.di;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkManager;
import com.utechat.BuildConfig;
import com.utechat.data.MainThreadExecutor;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.api.ApiService;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.api.ContactApi;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.data.repository.CurrentUserIdProvider;
import com.utechat.data.repository.HealthRepository;
import com.utechat.data.repository.fake.FakeAuthRepository;
import com.utechat.data.repository.fake.FakeChatRepository;
import com.utechat.data.repository.fake.FakeContactsRepository;
import com.utechat.data.repository.real.RealAuthRepository;
import com.utechat.data.repository.real.RealChatRepository;
import com.utechat.data.repository.real.RealContactsRepository;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.ChatWebSocketManager;
import com.utechat.data.sync.SyncEngine;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.data.sync.TypingTracker;
import com.utechat.data.worker.SendMessageWorker;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Service Locator thuần Java: một nơi ghép toàn bộ phụ thuộc của ứng dụng, không dùng Hilt/Dagger. */
public final class Injection {
    private static AuthRepository auth;
    private static ContactsRepository contacts;
    private static ChatRepository chat;
    private static HealthRepository health;
    private static SyncEngine syncEngine;
    private static ChatWebSocketManager wsManager;
    private static TypingTracker typingTracker;

    // Hook dành riêng cho kiểm thử instrumentation Espresso (tiêm repository thật trên Room in-memory khi Debug).
    @Nullable private static ChatRepository testChatOverride;
    @Nullable private static ContactsRepository testContactsOverride;

    // Luồng đĩa đơn tuần tự cho mọi thao tác ghi/xóa Room (clearAllTables, SyncEngine, SendMessageWorker).
    private static final Executor DISK = Executors.newSingleThreadExecutor();
    // Mạng danh bạ có hàng đợi riêng, không giữ luồng DISK khi đang chờ HTTP.
    private static final Executor CONTACT_NETWORK = Executors.newSingleThreadExecutor();
    // Hàng đợi mạng riêng cho SyncEngine (/api/sync & /api/sync/bootstrap).
    private static final Executor SYNC_NETWORK = Executors.newSingleThreadExecutor();
    // Hàng đợi mạng riêng cho các thao tác REST chat (tạo nhóm, tải lịch sử, đọc tin).
    private static final Executor CHAT_NETWORK = Executors.newSingleThreadExecutor();

    private Injection() { }

    /** Trả về luồng DISK đơn dùng chung cho cả repository lẫn SendMessageWorker. */
    public static Executor getDiskExecutor() {
        return DISK;
    }

    public static synchronized TypingTracker provideTypingTracker() {
        if (typingTracker == null) {
            typingTracker = new TypingTracker();
        }
        return typingTracker;
    }

    public static synchronized SyncEngine provideSyncEngine(Context context) {
        if (syncEngine == null) {
            Context appContext = context.getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(appContext);
            ChatApi chatApi = ApiClient.getRetrofit().create(ChatApi.class);
            SyncEventApplier applier = new SyncEventApplier(db);
            syncEngine = new SyncEngine(db, chatApi, applier, SessionManager.get(), SYNC_NETWORK, DISK);
        }
        return syncEngine;
    }

    public static synchronized ChatWebSocketManager provideWebSocketManager(Context context) {
        if (wsManager == null) {
            Context appContext = context.getApplicationContext();
            wsManager = new ChatWebSocketManager(
                    SessionManager.get(),
                    provideSyncEngine(appContext),
                    provideTypingTracker());
        }
        return wsManager;
    }

    public static synchronized AuthRepository provideAuthRepository(Context context) {
        if (auth == null) {
            Context appContext = context.getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(appContext);
            // Khi rời phiên (logout, xóa tài khoản, 401/4401): hủy đúng hàng đợi phiên cũ, ngắt WS, và xóa Room trên DISK.
            auth = useFake()
                    ? new FakeAuthRepository()
                    : new RealAuthRepository(
                            ApiClient.getRetrofit().create(AuthApi.class),
                            SessionManager.get(),
                            db.userDao(),
                            DISK,
                            db::clearAllTables,
                            MainThreadExecutor.INSTANCE,
                            departingSessionKey -> cleanupDepartingSession(appContext, departingSessionKey));
        }
        return auth;
    }

    /** Hủy duy nhất chuỗi WorkManager của phiên cũ và ngắt WebSocket, tuyệt đối không chạm phiên đăng nhập mới. */
    private static void cleanupDepartingSession(Context appContext, String departingSessionKey) {
        if (departingSessionKey != null && !departingSessionKey.trim().isEmpty()) {
            try {
                WorkManager.getInstance(appContext).cancelUniqueWork(SendMessageWorker.queueName(departingSessionKey));
            } catch (IllegalStateException ignored) {
                // Tránh crash nếu WorkManager chưa khởi tạo trong môi trường test tối giản.
            }
        }
        ChatWebSocketManager currentWs;
        synchronized (Injection.class) {
            currentWs = wsManager;
        }
        if (currentWs != null) {
            currentWs.disconnect();
        }
    }

    public static synchronized ContactsRepository provideContactsRepository(Context context) {
        if (testContactsOverride != null) {
            return testContactsOverride;
        }
        if (contacts == null) {
            if (useFake()) {
                FakeContactsRepository fake = new FakeContactsRepository(provideContactsSession(context));
                contacts = fake;
                // Xóa các LiveData cache cũ ngay khi Fake đăng xuất hoặc đăng nhập lại cùng tài khoản.
                provideAuthRepository(context).getCurrentUser().observeForever(user -> fake.onSessionChanged());
            } else {
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
        if (testChatOverride != null) {
            return testChatOverride;
        }
        if (chat == null) {
            if (useFake()) {
                chat = new FakeChatRepository();
            } else {
                Context appContext = context.getApplicationContext();
                // Bảo đảm AuthRepository đã đăng ký hook dọn phiên trên SessionManager trước khi chat chạy.
                provideAuthRepository(appContext);
                AppDatabase db = AppDatabase.getInstance(appContext);
                ChatApi chatApi = ApiClient.getRetrofit().create(ChatApi.class);
                SyncEventApplier applier = new SyncEventApplier(db);
                chat = new RealChatRepository(
                        db,
                        chatApi,
                        SessionManager.get(),
                        provideSyncEngine(appContext),
                        provideWebSocketManager(appContext),
                        applier,
                        provideTypingTracker(),
                        WorkManager.getInstance(appContext),
                        CHAT_NETWORK,
                        DISK,
                        MainThreadExecutor.INSTANCE);
            }
        }
        return chat;
    }

    public static synchronized HealthRepository provideHealthRepository() {
        // Luôn dùng API thật; bật Fake không được biến probe thành CONNECTED giả.
        if (health == null) health = new HealthRepository(ApiClient.getRetrofit().create(ApiService.class));
        return health;
    }

    @VisibleForTesting
    public static synchronized void overrideChatRepositoryForTest(@Nullable ChatRepository testChat) {
        testChatOverride = testChat;
    }

    @VisibleForTesting
    public static synchronized void overrideContactsRepositoryForTest(@Nullable ContactsRepository testContacts) {
        testContactsOverride = testContacts;
    }

    @VisibleForTesting
    public static synchronized void resetForTest() {
        testChatOverride = null;
        testContactsOverride = null;
        if (wsManager != null) {
            wsManager.disconnect();
        }
        auth = null;
        contacts = null;
        chat = null;
        health = null;
        syncEngine = null;
        wsManager = null;
        typingTracker = null;
    }

    private static boolean useFake() {
        // Chặn Fake thêm lần nữa ở runtime nếu sau này sửa nhầm buildConfig của Release.
        return BuildConfig.DEBUG && BuildConfig.USE_FAKE_DATA;
    }
}
