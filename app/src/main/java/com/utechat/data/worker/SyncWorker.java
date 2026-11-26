package com.utechat.data.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.SyncEngine;
import com.utechat.di.Injection;
import java.util.concurrent.TimeUnit;

/**
 * Worker đồng bộ nền định kỳ 15 phút (best-effort không dùng FCM — ADR-012, D-08):
 * - Mỗi phiên đăng nhập dùng đúng 1 lịch định kỳ "periodic-sync:&lt;sessionKey&gt;" với chính sách KEEP và yêu cầu có mạng.
 * - Lưu tường minh sessionKey vào inputData; tuyệt đối không thay thế bằng phiên hiện hành khi thiếu inputData.
 * - Ủy quyền hoàn toàn cho SyncEngine.syncBlocking(enqueuedSessionKey) dùng chung khóa single-flight để không tranh chấp sync_state.
 */
public class SyncWorker extends Worker {
    public static final String UNIQUE_WORK_PREFIX = "periodic-sync:";
    public static final String TAG_ALL = "periodic-sync";
    public static final String TAG_SESSION_PREFIX = "session:";
    public static final String KEY_SESSION_KEY = "sessionKey";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Tên công việc định kỳ duy nhất gắn theo sessionKey (không bao giờ chứa JWT). */
    @NonNull
    public static String queueName(@NonNull String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionKey không được rỗng");
        }
        return UNIQUE_WORK_PREFIX + sessionKey;
    }

    /** Đăng ký công việc đồng bộ định kỳ mỗi 15 phút theo phiên; KEEP giữ lịch cũ tránh reset bộ đếm thời gian. */
    public static void enqueuePeriodic(@NonNull WorkManager wm, @NonNull String sessionKey) {
        String uniqueName = queueName(sessionKey);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(KEY_SESSION_KEY, sessionKey)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(TAG_ALL)
                .addTag(TAG_SESSION_PREFIX + sessionKey)
                .build();
        wm.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** Hủy lịch đồng bộ định kỳ của phiên sắp rời đi (gọi khi logout / 401 / 4401). */
    public static void cancelForSession(@NonNull WorkManager wm, @Nullable String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            return;
        }
        wm.cancelUniqueWork(queueName(sessionKey));
    }

    @NonNull
    @Override
    public Result doWork() {
        // Đọc chặt sessionKey từ inputData lúc đăng ký; không bao giờ lấy thay bằng phiên đăng nhập hiện tại nếu thiếu.
        String enqueuedSessionKey = getInputData().getString(KEY_SESSION_KEY);
        SessionManager session = SessionManager.get();
        SyncEngine syncEngine = Injection.provideSyncEngine(getApplicationContext());
        return executePeriodicSync(session, syncEngine, enqueuedSessionKey);
    }

    /**
     * Lõi thực thi đồng bộ định kỳ có thể kiểm thử trực tiếp trên JVM:
     * bỏ qua không gọi mạng nếu phiên đã đổi hoặc thiếu sessionKey, dùng chung mutex của SyncEngine.syncBlocking,
     * và kiểm tra lại phiên sau khi xong để không bao giờ retry cho phiên đã đăng xuất.
     */
    @NonNull
    public static Result executePeriodicSync(
            @Nullable SessionManager session,
            @NonNull SyncEngine syncEngine,
            @Nullable String enqueuedSessionKey) {
        if (session == null || enqueuedSessionKey == null || enqueuedSessionKey.trim().isEmpty()) {
            return Result.success();
        }
        if (!enqueuedSessionKey.equals(session.sessionKey())) {
            return Result.success();
        }
        boolean ok = syncEngine.syncBlocking(enqueuedSessionKey);
        if (!enqueuedSessionKey.equals(session.sessionKey())) {
            return Result.success();
        }
        return ok ? Result.success() : Result.retry();
    }
}
