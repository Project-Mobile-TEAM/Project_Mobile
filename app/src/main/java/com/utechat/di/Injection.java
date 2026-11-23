package com.utechat.di;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkManager;
import com.google.gson.JsonObject;
import com.utechat.BuildConfig;
import com.utechat.MainActivity;
import com.utechat.data.MainThreadExecutor;
import com.utechat.data.call.CallSessionCoordinator;
import com.utechat.data.call.StreamWebRtcCallEngine;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.media.LocationFetchHelper;
import com.utechat.data.media.MediaDownloadManager;
import com.utechat.data.media.MediaPreparationHelper;
import com.utechat.data.notifications.ActiveRoomTracker;
import com.utechat.data.notifications.NotificationHelper;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.api.ApiService;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.remote.api.CallApi;
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
import com.utechat.data.translate.FakeMessageTranslator;
import com.utechat.data.translate.MessageTranslationManager;
import com.utechat.data.translate.RealMlKitTranslator;
import com.utechat.data.worker.SendMessageWorker;
import com.utechat.data.worker.SyncWorker;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Service Locator thuần Java: một nơi ghép toàn bộ phụ thuộc của ứng dụng (Phase 2–6), không dùng Hilt/Dagger.
 * Tách riêng luồng MEDIA_IO và CALL_NETWORK khỏi CHAT_NETWORK để truyền tải 50MB không chặn báo hiệu cuộc gọi (WRN-02).
 */
public final class Injection {
    private static AuthRepository auth;
    private static ContactsRepository contacts;
    private static ChatRepository chat;
    private static HealthRepository health;
    private static SyncEngine syncEngine;
    private static ChatWebSocketManager wsManager;
    private static TypingTracker typingTracker;
    private static ActiveRoomTracker activeRoomTracker;
    private static NotificationHelper notificationHelper;
    private static MediaPreparationHelper mediaPreparationHelper;
    private static MediaDownloadManager mediaDownloadManager;
    private static LocationFetchHelper locationFetchHelper;
    private static CallSessionCoordinator callCoordinator;
    private static MessageTranslationManager translationManager;

    // Hook dành riêng cho kiểm thử instrumentation Espresso (tiêm repository thật trên Room in-memory khi Debug).
    @Nullable private static ChatRepository testChatOverride;
    @Nullable private static ContactsRepository testContactsOverride;

    // Luồng đĩa đơn tuần tự cho mọi thao tác ghi/xóa Room và dọn thư mục media khi chuyển phiên.
    private static final Executor DISK = Executors.newSingleThreadExecutor();
    // Mạng danh bạ có hàng đợi riêng, không giữ luồng DISK khi đang chờ HTTP.
    private static final Executor CONTACT_NETWORK = Executors.newSingleThreadExecutor();
    // Hàng đợi mạng riêng cho SyncEngine (/api/sync & /api/sync/bootstrap).
    private static final Executor SYNC_NETWORK = Executors.newSingleThreadExecutor();
    // Hàng đợi mạng riêng cho các thao tác REST chat (tạo nhóm, tải lịch sử, đọc tin).
    private static final Executor CHAT_NETWORK = Executors.newSingleThreadExecutor();
    // Nhóm luồng riêng cho chuẩn bị/tải tệp đa phương tiện (<= 50MB), tách khỏi CHAT_NETWORK và CALL_NETWORK.
    private static final Executor MEDIA_IO = Executors.newFixedThreadPool(2);
    // Luồng mạng riêng cho REST cuộc gọi thoại (/api/calls/**) để không bao giờ bị HoL-block bởi upload/download media.
    private static final Executor CALL_NETWORK = Executors.newSingleThreadExecutor();

    private Injection() { }

    /** Trả về luồng DISK đơn dùng chung cho cả repository lẫn SendMessageWorker. */
    public static Executor getDiskExecutor() {
        return DISK;
    }

    /** Trả về luồng MEDIA_IO riêng cho nén/tải media đa phương tiện. */
    public static Executor getMediaIoExecutor() {
        return MEDIA_IO;
    }

    public static synchronized TypingTracker provideTypingTracker() {
        if (typingTracker == null) {
            typingTracker = new TypingTracker();
        }
        return typingTracker;
    }

    public static synchronized ActiveRoomTracker provideActiveRoomTracker() {
        if (activeRoomTracker == null) {
            activeRoomTracker = new ActiveRoomTracker();
        }
        return activeRoomTracker;
    }

    public static synchronized NotificationHelper provideNotificationHelper(Context context) {
        if (notificationHelper == null) {
            Context appContext = context.getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(appContext);
            notificationHelper = new NotificationHelper(appContext, db, SessionManager.get());
        }
        return notificationHelper;
    }

    public static synchronized MediaPreparationHelper provideMediaPreparationHelper() {
        if (mediaPreparationHelper == null) {
            mediaPreparationHelper = new MediaPreparationHelper();
        }
        return mediaPreparationHelper;
    }

    public static synchronized MediaDownloadManager provideMediaDownloadManager(Context context) {
        if (mediaDownloadManager == null) {
            Context appContext = context.getApplicationContext();
            mediaDownloadManager = new MediaDownloadManager(
                    appContext,
                    ApiClient.getMediaOkHttpClient(),
                    AppDatabase.getInstance(appContext).transferDao(),
                    MEDIA_IO,
                    DISK,
                    MainThreadExecutor.INSTANCE,
                    SessionManager.get());
        }
        return mediaDownloadManager;
    }

    public static synchronized LocationFetchHelper provideLocationFetchHelper() {
        if (locationFetchHelper == null) {
            locationFetchHelper = new LocationFetchHelper();
        }
        return locationFetchHelper;
    }

    public static synchronized CallSessionCoordinator provideCallSessionCoordinator(Context context) {
        if (callCoordinator == null) {
            Context appContext = context.getApplicationContext();
            if (!useFake()) {
                // Bảo đảm hook dọn phiên trên SessionManager luôn sẵn sàng trước khi cuộc gọi chạy.
                provideAuthRepository(appContext);
            }
            Handler mainHandler = new Handler(Looper.getMainLooper());
            CallSessionCoordinator.DelayScheduler mainDelayScheduler = (delayMs, task) -> {
                mainHandler.postDelayed(task, Math.max(0L, delayMs));
                return () -> mainHandler.removeCallbacks(task);
            };
            // Lambda lười phá vỡ vòng khởi tạo giữa CallSessionCoordinator và ChatWebSocketManager.
            CallSessionCoordinator.SignalingSender lazySender =
                    (frameType, payload) -> provideWebSocketManager(appContext).sendCallSignalingFrame(frameType, payload);

            callCoordinator = new CallSessionCoordinator(
                    ApiClient.getRetrofit().create(CallApi.class),
                    SessionManager.get(),
                    () -> new StreamWebRtcCallEngine(appContext),
                    lazySender,
                    mainDelayScheduler,
                    SystemClock::elapsedRealtime,
                    CALL_NETWORK,
                    MainThreadExecutor.INSTANCE);

            // Cuộc gọi đến: luôn đăng thông báo kèm sessionKey; nếu MainActivity đang mở thì điều hướng ngay sang CallFragment.
            NotificationHelper notif = provideNotificationHelper(appContext);
            callCoordinator.setIncomingCallAlertListener(new CallSessionCoordinator.IncomingCallAlertListener() {
                @Override
                public void onIncomingCallAlert(@NonNull CallSessionCoordinator.CallUiState state) {
                    if (state.callId == null) {
                        return;
                    }
                    String sessionKey = SessionManager.get().sessionKey();
                    notif.showIncomingCallNotification(state.callId, state.conversationId, state.peerName);
                    if (sessionKey != null) {
                        MainActivity.navigateToIncomingCallIfResumed(state.callId, sessionKey);
                    }
                }

                @Override
                public void onCancelIncomingCallAlert(@NonNull String callId) {
                    notif.cancelIncomingCallNotification(callId);
                }
            });

            CallSessionCoordinator.setInstance(callCoordinator);
            CallSessionCoordinator.setProvider(Injection::provideCallSessionCoordinator);
        }
        return callCoordinator;
    }

    public static synchronized MessageTranslationManager provideMessageTranslationManager(Context context) {
        if (translationManager == null) {
            Context appContext = context.getApplicationContext();
            ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
            translationManager = new MessageTranslationManager(
                    AppDatabase.getInstance(appContext).translationDao(),
                    useFake() ? new FakeMessageTranslator() : new RealMlKitTranslator(),
                    cm,
                    SessionManager.get(),
                    DISK,
                    MainThreadExecutor.INSTANCE);
        }
        return translationManager;
    }

    public static synchronized SyncEngine provideSyncEngine(Context context) {
        if (syncEngine == null) {
            Context appContext = context.getApplicationContext();
            if (!useFake()) {
                provideAuthRepository(appContext);
            }
            AppDatabase db = AppDatabase.getInstance(appContext);
            ChatApi chatApi = ApiClient.getRetrofit().create(ChatApi.class);
            SyncEventApplier applier = new SyncEventApplier(db);
            syncEngine = new SyncEngine(db, chatApi, applier, SessionManager.get(), SYNC_NETWORK, DISK);

            // Gắn callback thông báo chỉ chạy SAU KHI transaction Room của /api/sync đã commit (D-05, D-08).
            NotificationHelper notif = provideNotificationHelper(appContext);
            ActiveRoomTracker roomTracker = provideActiveRoomTracker();
            syncEngine.setNotificationCallback((sessionKey, userId, outcome) ->
                    notif.notifyBatch(
                            userId,
                            sessionKey,
                            outcome.newlyInsertedMessages,
                            outcome.missedCalls,
                            roomTracker,
                            () -> sessionKey.equals(SessionManager.get().sessionKey())));
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
            if (!useFake()) {
                CallSessionCoordinator coordinator = provideCallSessionCoordinator(appContext);
                wsManager.setCallSignalListener(new ChatWebSocketManager.CallSignalListener() {
                    @Override
                    public void onCallFrame(@NonNull String type, @NonNull JsonObject payload) {
                        coordinator.onWsFrame(type, payload);
                    }

                    @Override
                    public void onSocketReconnected() {
                        coordinator.onWebSocketReconnected();
                    }
                });
            }
        }
        return wsManager;
    }

    public static synchronized AuthRepository provideAuthRepository(Context context) {
        if (auth == null) {
            Context appContext = context.getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(appContext);
            // Khi rời phiên (logout, xóa tài khoản, 401/4401 hoặc đổi thẳng sang tài khoản khác):
            // hủy hàng đợi gửi & sync phiên cũ, ngắt WS, hủy download/call/thông báo và dọn thư mục media trên DISK.
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

    /**
     * Dọn sạch trọn vẹn mọi tài nguyên của phiên rời đi (logout, 401, 4401 hoặc đổi tài khoản A -> B, T-06-23):
     * hủy SendMessageWorker & SyncWorker, hủy lượt tải MediaDownloadManager, ngắt WS, xóa cuộc gọi/thông báo/active room,
     * và xóa đệ quy pending_media/, media_cache/, camera/ trên luồng DISK tuần tự.
     */
    private static void cleanupDepartingSession(Context appContext, String departingSessionKey) {
        if (departingSessionKey != null && !departingSessionKey.trim().isEmpty()) {
            try {
                WorkManager wm = WorkManager.getInstance(appContext);
                wm.cancelUniqueWork(SendMessageWorker.queueName(departingSessionKey));
                wm.cancelUniqueWork(SyncWorker.queueName(departingSessionKey));
            } catch (IllegalStateException ignored) {
                // Tránh crash nếu WorkManager chưa khởi tạo trong môi trường test tối giản.
            }
        }

        ChatWebSocketManager currentWs;
        CallSessionCoordinator currentCall;
        NotificationHelper currentNotif;
        ActiveRoomTracker currentRoomTracker;
        MediaDownloadManager currentDownloadManager;
        synchronized (Injection.class) {
            currentWs = wsManager;
            currentCall = callCoordinator;
            currentNotif = notificationHelper;
            currentRoomTracker = activeRoomTracker;
            currentDownloadManager = mediaDownloadManager;
        }

        if (currentDownloadManager != null && departingSessionKey != null && !departingSessionKey.trim().isEmpty()) {
            currentDownloadManager.cancelDownloadsForSession(departingSessionKey);
        }
        if (currentWs != null) {
            currentWs.disconnect();
        }
        if (currentCall != null) {
            currentCall.onSessionCleared();
        }
        if (currentNotif != null) {
            currentNotif.cancelAll();
        }
        if (currentRoomTracker != null) {
            currentRoomTracker.clear();
        }

        // Xếp xóa thư mục tạm media trên luồng DISK đơn tuần tự để chạy trước mọi thao tác ghi của tài khoản mới.
        DISK.execute(() -> {
            deleteDirectoryContents(new File(appContext.getFilesDir(), "pending_media"));
            deleteDirectoryContents(new File(appContext.getCacheDir(), "media_cache"));
            deleteDirectoryContents(new File(appContext.getCacheDir(), "camera"));
        });
    }

    private static void deleteDirectoryContents(@Nullable File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
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
        if (callCoordinator != null) {
            callCoordinator.onSessionCleared();
        }
        if (notificationHelper != null) {
            notificationHelper.cancelAll();
        }
        if (activeRoomTracker != null) {
            activeRoomTracker.clear();
        }
        CallSessionCoordinator.setInstance(null);
        CallSessionCoordinator.setProvider(null);
        auth = null;
        contacts = null;
        chat = null;
        health = null;
        syncEngine = null;
        wsManager = null;
        typingTracker = null;
        activeRoomTracker = null;
        notificationHelper = null;
        mediaPreparationHelper = null;
        mediaDownloadManager = null;
        locationFetchHelper = null;
        callCoordinator = null;
        translationManager = null;
    }

    private static boolean useFake() {
        // Chặn Fake thêm lần nữa ở runtime nếu sau này sửa nhầm buildConfig của Release.
        return BuildConfig.DEBUG && BuildConfig.USE_FAKE_DATA;
    }
}
