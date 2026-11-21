package com.utechat.data.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.Gson;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.SyncStateEntity;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.session.SessionManager;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import retrofit2.Response;

/**
 * Động cơ đồng bộ đơn luồng logic (single-flight) bảo đảm thứ tự sự kiện và tính nhất quán con trỏ (D-06, D-07):
 * - Gộp mọi lời gọi requestSync() dồn dập bằng cặp AtomicBoolean running + resyncRequested.
 * - Tự chạy /api/sync/bootstrap khi chưa có cursor hoặc khi gặp HTTP 410 SYNC_CURSOR_EXPIRED.
 * - Chỉ cập nhật sync_state.lastSyncEventId bên trong CÙNG transaction Room với SyncEventApplier.applyBatch.
 * - Khi server trả retryAfterMs (barrier đang giữ giao dịch), đặt đúng 1 hẹn giờ không chặn luồng để kéo lại.
 */
public class SyncEngine {
    /** Trừu tượng bộ lập lịch trễ cho phép tiêm FakeScheduler trong bài kiểm thử JVM không cần sleep. */
    public interface DelayScheduler {
        interface Cancellable {
            void cancel();
        }

        Cancellable schedule(long delayMs, Runnable task);
    }

    /** Callback thông báo cho ChatWebSocketManager biết catch-up đã hoàn tất sạch hay còn chờ barrier/lỗi. */
    public interface CatchUpListener {
        void onCatchUpStatus(@NonNull String sessionKey, boolean caughtUp);
    }

    private static final int SYNC_PAGE_LIMIT = 100;
    private static final long MIN_BARRIER_RETRY_MS = 1_000L;
    private static final Gson GSON = new Gson();
    private static volatile DelayScheduler sharedDefaultScheduler;

    private final AppDatabase db;
    private final ChatApi api;
    private final SyncEventApplier applier;
    private final SessionManager session;
    private final Executor syncNetworkExecutor;
    private final Executor diskExecutor;
    private final DelayScheduler scheduler;
    private final LongSupplier clockMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean resyncRequested = new AtomicBoolean(false);
    private final MutableLiveData<Boolean> syncing = new MutableLiveData<>(false);

    private volatile boolean foregroundActive = true;
    @Nullable private volatile CatchUpListener catchUpListener;
    @Nullable private DelayScheduler.Cancellable pendingBarrierRetry;

    public SyncEngine(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @NonNull SyncEventApplier applier,
            @NonNull SessionManager session,
            @NonNull Executor syncNetworkExecutor,
            @NonNull Executor diskExecutor) {
        this(db, api, applier, session, syncNetworkExecutor, diskExecutor, defaultScheduler(), System::currentTimeMillis);
    }

    public SyncEngine(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @NonNull SyncEventApplier applier,
            @NonNull SessionManager session,
            @NonNull Executor syncNetworkExecutor,
            @NonNull Executor diskExecutor,
            @NonNull DelayScheduler scheduler,
            @NonNull LongSupplier clockMs) {
        this.db = db;
        this.api = api;
        this.applier = applier;
        this.session = session;
        this.syncNetworkExecutor = syncNetworkExecutor;
        this.diskExecutor = diskExecutor;
        this.scheduler = scheduler;
        this.clockMs = clockMs;
    }

    /** Trả về bộ lập lịch daemon mặc định dùng chung trong ứng dụng Android. */
    public static DelayScheduler defaultScheduler() {
        if (sharedDefaultScheduler == null) {
            synchronized (SyncEngine.class) {
                if (sharedDefaultScheduler == null) {
                    ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
                        Thread t = new Thread(r, "utechat-sync-timer");
                        t.setDaemon(true);
                        return t;
                    });
                    exec.setRemoveOnCancelPolicy(true);
                    sharedDefaultScheduler = (delayMs, task) -> {
                        ScheduledFuture<?> future = exec.schedule(task, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
                        return () -> future.cancel(false);
                    };
                }
            }
        }
        return sharedDefaultScheduler;
    }

    public LiveData<Boolean> isSyncing() {
        return syncing;
    }

    public void setCatchUpListener(@Nullable CatchUpListener listener) {
        this.catchUpListener = listener;
    }

    /** Đánh dấu trạng thái foreground/kết nối: khi mất kết nối hoặc đăng xuất sẽ hủy ngay hẹn giờ barrier. */
    public synchronized void setForegroundActive(boolean active) {
        this.foregroundActive = active;
        if (!active) {
            cancelPendingBarrierRetryLocked();
        }
    }

    /** Hủy mọi hẹn giờ thử lại barrier đang chờ (gọi khi disconnect hoặc đổi phiên). */
    public synchronized void cancelPendingRetry() {
        cancelPendingBarrierRetryLocked();
    }

    /**
     * Yêu cầu đồng bộ an toàn single-flight: nhiều tín hiệu liên tiếp được gộp lại mà không bao giờ chạy song song /sync.
     */
    public void requestSync() {
        cancelPendingRetry();
        resyncRequested.set(true);
        if (running.compareAndSet(false, true)) {
            syncing.postValue(true);
            syncNetworkExecutor.execute(this::drainLoop);
        }
    }

    private void drainLoop() {
        try {
            while (resyncRequested.getAndSet(false)) {
                runOnePass();
            }
        } finally {
            running.set(false);
            syncing.postValue(false);
            // Nếu có requestSync() chen vào đúng sát thời điểm kết thúc, khởi chạy thêm một vòng an toàn.
            if (resyncRequested.get() && running.compareAndSet(false, true)) {
                syncing.postValue(true);
                syncNetworkExecutor.execute(this::drainLoop);
            }
        }
    }

    private void runOnePass() {
        String sessionKey = session.sessionKey();
        String myUserId = session.getUserId();
        if (sessionKey == null || myUserId == null) {
            return;
        }

        try {
            SyncStateEntity state = runOnDisk(() -> db.syncStateDao().get(myUserId));
            if (!sessionKey.equals(session.sessionKey())) {
                return;
            }

            if (state == null) {
                if (!runBootstrap(sessionKey, myUserId)) {
                    notifyCatchUp(sessionKey, false);
                    return;
                }
                state = runOnDisk(() -> db.syncStateDao().get(myUserId));
                if (state == null || !sessionKey.equals(session.sessionKey())) {
                    notifyCatchUp(sessionKey, false);
                    return;
                }
            }

            long cursor = state.getLastSyncEventId();
            boolean rebootstrappedOnce = false;

            while (sessionKey.equals(session.sessionKey())) {
                Response<ChatDtos.SyncResponse> response = api.sync(cursor, SYNC_PAGE_LIMIT).execute();
                if (!sessionKey.equals(session.sessionKey())) {
                    return;
                }

                // Hồi phục tự động khi con trỏ quá cũ (> 30 ngày) hoặc vượt sequence trên server (D-06).
                if (response.code() == 410 && isCursorExpiredError(response)) {
                    if (rebootstrappedOnce) {
                        notifyCatchUp(sessionKey, false);
                        return;
                    }
                    rebootstrappedOnce = true;
                    if (!runBootstrap(sessionKey, myUserId)) {
                        notifyCatchUp(sessionKey, false);
                        return;
                    }
                    SyncStateEntity resetState = runOnDisk(() -> db.syncStateDao().get(myUserId));
                    if (resetState == null || !sessionKey.equals(session.sessionKey())) {
                        notifyCatchUp(sessionKey, false);
                        return;
                    }
                    cursor = resetState.getLastSyncEventId();
                    continue;
                }

                if (!response.isSuccessful() || response.body() == null) {
                    notifyCatchUp(sessionKey, false);
                    return;
                }

                ChatDtos.SyncResponse body = response.body();
                AtomicReference<Map<String, Long>> deliveredTargets = new AtomicReference<>(Collections.emptyMap());

                // Áp dụng toàn bộ lô sự kiện VÀ nâng con trỏ sync trong CÙNG một transaction Room trên DISK.
                Boolean applied = runOnDisk(() -> session.runIfCurrent(sessionKey, () ->
                        db.runInTransaction(() -> {
                            Map<String, Long> incoming = applier.applyBatch(myUserId, body.events);
                            db.syncStateDao().advance(myUserId, body.nextCursor, clockMs.getAsLong());
                            deliveredTargets.set(incoming);
                        })
                ));

                if (!Boolean.TRUE.equals(applied)) {
                    return;
                }

                // Gửi biên nhận DELIVERED sau khi transaction đã commit chắc chắn xuống đĩa (D-11).
                sendDeliveredWatermarks(sessionKey, deliveredTargets.get());

                cursor = body.nextCursor;

                if (body.hasMore) {
                    continue;
                }

                if (body.retryAfterMs != null) {
                    // Server đang có giao dịch bị barrier giữ lại: giữ trạng thái chưa catch-up và đặt đúng 1 hẹn giờ.
                    notifyCatchUp(sessionKey, false);
                    scheduleBarrierRetry(sessionKey, Math.max(MIN_BARRIER_RETRY_MS, body.retryAfterMs));
                    return;
                }

                notifyCatchUp(sessionKey, true);
                return;
            }
        } catch (Exception ignored) {
            notifyCatchUp(sessionKey, false);
        }
    }

    private boolean runBootstrap(String sessionKey, String myUserId) throws Exception {
        Response<ChatDtos.BootstrapResponse> response = api.bootstrap().execute();
        if (!sessionKey.equals(session.sessionKey()) || !response.isSuccessful() || response.body() == null) {
            return false;
        }
        ChatDtos.BootstrapResponse snap = response.body();
        Boolean applied = runOnDisk(() -> session.runIfCurrent(sessionKey, () ->
                db.runInTransaction(() -> applier.applyBootstrapSnapshot(myUserId, snap, clockMs.getAsLong()))
        ));
        return Boolean.TRUE.equals(applied);
    }

    private void sendDeliveredWatermarks(String sessionKey, @Nullable Map<String, Long> highestByConversation) {
        if (highestByConversation == null || highestByConversation.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : highestByConversation.entrySet()) {
            if (!sessionKey.equals(session.sessionKey())) return;
            Long maxId = entry.getValue();
            if (maxId == null || maxId <= 0) continue;
            try {
                api.markDelivered(entry.getKey(), new ChatDtos.WatermarkRequest(maxId)).execute();
            } catch (Exception ignored) {
                // Biên nhận DELIVERED là nỗ lực tốt nhất; đợt sync hoặc đọc tin sau sẽ tiếp tục cập nhật.
            }
        }
    }

    private synchronized void scheduleBarrierRetry(String sessionKey, long delayMs) {
        cancelPendingBarrierRetryLocked();
        if (!foregroundActive || !sessionKey.equals(session.sessionKey())) {
            return;
        }
        pendingBarrierRetry = scheduler.schedule(delayMs, () -> {
            synchronized (SyncEngine.this) {
                pendingBarrierRetry = null;
                if (!foregroundActive || !sessionKey.equals(session.sessionKey())) {
                    return;
                }
            }
            requestSync();
        });
    }

    private void cancelPendingBarrierRetryLocked() {
        if (pendingBarrierRetry != null) {
            pendingBarrierRetry.cancel();
            pendingBarrierRetry = null;
        }
    }

    private void notifyCatchUp(String sessionKey, boolean caughtUp) {
        CatchUpListener listener = catchUpListener;
        if (listener != null) {
            listener.onCatchUpStatus(sessionKey, caughtUp);
        }
    }

    private <T> T runOnDisk(Callable<T> action) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        diskExecutor.execute(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private static boolean isCursorExpiredError(Response<?> response) {
        try {
            if (response.errorBody() == null) return true;
            String raw = response.errorBody().string();
            ChatDtos.ErrorBody body = GSON.fromJson(raw, ChatDtos.ErrorBody.class);
            if (body != null && body.error != null && body.error.code != null) {
                return "SYNC_CURSOR_EXPIRED".equals(body.error.code);
            }
        } catch (Exception ignored) {
            // 410 GONE luôn coi là hết hạn con trỏ nếu body không phải JSON chi tiết.
        }
        return true;
    }
}
