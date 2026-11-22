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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import retrofit2.Response;

/**
 * Động cơ đồng bộ đơn luồng logic (single-flight) bảo đảm thứ tự sự kiện và tính nhất quán con trỏ (D-06, D-07, D-08):
 * - Dùng chung syncLock giữa requestSync() (foreground/WS) và syncBlocking(sessionKey) (SyncWorker 15 phút)
 *   để không bao giờ có 2 tiến trình cùng ghi sync_state.
 * - Tự chạy /api/sync/bootstrap khi chưa có cursor hoặc khi gặp HTTP 410 SYNC_CURSOR_EXPIRED (tuyệt đối không bắn thông báo lúc bootstrap).
 * - Chỉ cập nhật sync_state.lastSyncEventId bên trong CÙNG transaction Room với SyncEventApplier.applyBatchDetailed,
 *   và chỉ phát thông báo incremental SAU KHI transaction đã commit và phiên vẫn khớp.
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

    /** Callback phát thông báo sau khi lô /api/sync đã commit thành công xuống Room (không bao giờ gọi khi bootstrap). */
    public interface BatchNotificationCallback {
        void onBatchCommitted(
                @NonNull String sessionKey,
                @NonNull String myUserId,
                @NonNull SyncEventApplier.SyncBatchOutcome outcome);
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

    private final Object syncLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean resyncRequested = new AtomicBoolean(false);
    private final MutableLiveData<Boolean> syncing = new MutableLiveData<>(false);
    private volatile boolean lastPassSucceeded;
    private long drainSequence = 0L;
    @Nullable private DrainState currentDrain;

    private volatile boolean foregroundActive = true;
    @Nullable private volatile CatchUpListener catchUpListener;
    @Nullable private volatile BatchNotificationCallback notificationCallback;
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

    /** Gắn callback nhận sự kiện tin mới/cuộc gọi nhỡ sau khi commit Room (được Injection nối ở 06-08). */
    public void setNotificationCallback(@Nullable BatchNotificationCallback callback) {
        this.notificationCallback = callback;
    }

    /** Đánh dấu trạng thái foreground/kết nối: khi mất kết nối hoặc đăng xuất sẽ hủy ngay hẹn giờ barrier và đánh thức waiter. */
    public synchronized void setForegroundActive(boolean active) {
        this.foregroundActive = active;
        if (!active) {
            cancelPendingBarrierRetryLocked();
            synchronized (syncLock) {
                syncLock.notifyAll();
            }
        }
    }

    /** Hủy mọi hẹn giờ thử lại barrier đang chờ (gọi khi disconnect hoặc đổi phiên) và đánh thức các luồng chờ. */
    public synchronized void cancelPendingRetry() {
        cancelPendingBarrierRetryLocked();
        synchronized (syncLock) {
            syncLock.notifyAll();
        }
    }

    /**
     * Yêu cầu đồng bộ không chặn an toàn single-flight:
     * nhiều tín hiệu dồn dập được gộp chung vào cờ resyncRequested dưới syncLock mà không chạy song song /sync.
     */
    public void requestSync() {
        cancelPendingRetry();
        DrainState started = null;
        synchronized (syncLock) {
            resyncRequested.set(true);
            if (running.compareAndSet(false, true)) {
                started = new DrainState(++drainSequence, session.sessionKey());
                currentDrain = started;
                syncing.postValue(true);
            }
        }
        if (started != null) {
            final DrainState drain = started;
            try {
                syncNetworkExecutor.execute(() -> drainLoop(drain));
            } catch (Throwable t) {
                finishDrainOnError(drain);
                throw t;
            }
        }
    }

    /**
     * Đồng bộ đồng bộ theo phiên dùng chung single-flight mutex với requestSync() (dành cho SyncWorker 15 phút — D-08):
     * - Nếu phiên đã đổi: trả true ngay để Worker thoát sạch mà không retry.
     * - Nếu chưa có drain nào đang chạy: chiếm quyền chạy drainLoop ngay ngoài syncLock.
     * - Nếu đã có drain đang chạy: đặt resyncRequested = true và chờ có thể bị interrupt cho tới khi đúng drain kết thúc.
     * - Kết quả trả về khớp đúng với expectedSessionKey, không bao giờ dùng nhầm cờ toàn cục của phiên sau.
     */
    public boolean syncBlocking(@NonNull String expectedSessionKey) {
        if (!expectedSessionKey.equals(session.sessionKey())) {
            return true;
        }
        cancelPendingRetry();

        while (true) {
            DrainState claimed = null;
            DrainState joined = null;
            synchronized (syncLock) {
                if (!expectedSessionKey.equals(session.sessionKey())) {
                    return true;
                }
                resyncRequested.set(true);
                if (running.compareAndSet(false, true)) {
                    claimed = new DrainState(++drainSequence, expectedSessionKey);
                    currentDrain = claimed;
                    syncing.postValue(true);
                } else {
                    joined = currentDrain;
                }
            }

            if (claimed != null) {
                drainLoop(claimed);
                synchronized (syncLock) {
                    if (!expectedSessionKey.equals(session.sessionKey())) {
                        return true;
                    }
                    Boolean sessionOutcome = claimed.resultBySession.get(expectedSessionKey);
                    return sessionOutcome != null ? sessionOutcome : claimed.lastPassSucceeded;
                }
            }

            if (joined != null) {
                synchronized (syncLock) {
                    while (!joined.finished) {
                        if (!expectedSessionKey.equals(session.sessionKey())) {
                            return true;
                        }
                        try {
                            syncLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return true;
                        }
                    }
                    if (!expectedSessionKey.equals(session.sessionKey())) {
                        return true;
                    }
                    Boolean sessionOutcome = joined.resultBySession.get(expectedSessionKey);
                    if (sessionOutcome != null) {
                        return sessionOutcome;
                    }
                }
                // Drain vừa xong thuộc phiên khác chưa chạy lượt nào cho expectedSessionKey -> quay lại vòng để chạy lượt riêng.
                continue;
            }
            return true;
        }
    }

    /**
     * Vòng lặp rút cạn single-flight: kiểm tra resyncRequested, hạ running và notifyAll() diễn ra NGUYÊN TỬ
     * trong cùng khối synchronized (syncLock); toàn bộ HTTP và Room luôn chạy BÊN NGOÀI syncLock.
     */
    private void drainLoop(@NonNull DrainState drain) {
        boolean normalExit = false;
        try {
            while (true) {
                synchronized (syncLock) {
                    boolean shouldRun = resyncRequested.getAndSet(false);
                    if (!shouldRun) {
                        drain.finished = true;
                        if (currentDrain == drain) {
                            currentDrain = null;
                        }
                        running.set(false);
                        syncing.postValue(false);
                        syncLock.notifyAll();
                        normalExit = true;
                        return;
                    }
                }

                String passSessionKey = session.sessionKey();
                boolean passOk = runOnePass(passSessionKey);
                synchronized (syncLock) {
                    lastPassSucceeded = passOk;
                    drain.lastPassSucceeded = passOk;
                    if (passSessionKey != null) {
                        drain.resultBySession.put(passSessionKey, passOk);
                    }
                    // Đánh thức waiter sớm nếu phiên của chúng vừa bị đăng xuất/thay đổi giữa lượt sync.
                    syncLock.notifyAll();
                }
            }
        } catch (Throwable t) {
            synchronized (syncLock) {
                lastPassSucceeded = false;
                drain.lastPassSucceeded = false;
                if (drain.initiatorSessionKey != null) {
                    drain.resultBySession.put(drain.initiatorSessionKey, false);
                }
            }
            throw t;
        } finally {
            if (!normalExit) {
                finishDrainOnError(drain);
            }
        }
    }

    private void finishDrainOnError(@NonNull DrainState drain) {
        synchronized (syncLock) {
            lastPassSucceeded = false;
            drain.lastPassSucceeded = false;
            drain.finished = true;
            if (currentDrain == drain) {
                currentDrain = null;
            }
            running.set(false);
            syncing.postValue(false);
            syncLock.notifyAll();
        }
    }

    /** Chạy 1 lượt đồng bộ (bootstrap nếu cần + kéo phân trang /api/sync); trả true nếu bắt kịp sạch hoặc phiên đã trống. */
    private boolean runOnePass(@Nullable String capturedSessionKey) {
        String sessionKey = capturedSessionKey != null ? capturedSessionKey : session.sessionKey();
        String myUserId = session.getUserId();
        if (sessionKey == null || myUserId == null || !sessionKey.equals(session.sessionKey())) {
            return true;
        }

        try {
            SyncStateEntity state = runOnDisk(() -> db.syncStateDao().get(myUserId));
            if (!sessionKey.equals(session.sessionKey())) {
                return true;
            }

            if (state == null) {
                if (!runBootstrap(sessionKey, myUserId)) {
                    notifyCatchUp(sessionKey, false);
                    return false;
                }
                state = runOnDisk(() -> db.syncStateDao().get(myUserId));
                if (state == null || !sessionKey.equals(session.sessionKey())) {
                    if (sessionKey.equals(session.sessionKey())) {
                        notifyCatchUp(sessionKey, false);
                        return false;
                    }
                    return true;
                }
            }

            long cursor = state.getLastSyncEventId();
            boolean rebootstrappedOnce = false;

            while (sessionKey.equals(session.sessionKey())) {
                Response<ChatDtos.SyncResponse> response = api.sync(cursor, SYNC_PAGE_LIMIT).execute();
                if (!sessionKey.equals(session.sessionKey())) {
                    return true;
                }

                // Hồi phục tự động khi con trỏ quá cũ (> 30 ngày) hoặc vượt sequence trên server (D-06).
                if (response.code() == 410 && isCursorExpiredError(response)) {
                    if (rebootstrappedOnce) {
                        notifyCatchUp(sessionKey, false);
                        return false;
                    }
                    rebootstrappedOnce = true;
                    if (!runBootstrap(sessionKey, myUserId)) {
                        notifyCatchUp(sessionKey, false);
                        return false;
                    }
                    SyncStateEntity resetState = runOnDisk(() -> db.syncStateDao().get(myUserId));
                    if (resetState == null || !sessionKey.equals(session.sessionKey())) {
                        if (sessionKey.equals(session.sessionKey())) {
                            notifyCatchUp(sessionKey, false);
                            return false;
                        }
                        return true;
                    }
                    cursor = resetState.getLastSyncEventId();
                    continue;
                }

                if (!response.isSuccessful() || response.body() == null) {
                    notifyCatchUp(sessionKey, false);
                    return false;
                }

                ChatDtos.SyncResponse body = response.body();
                AtomicReference<SyncEventApplier.SyncBatchOutcome> outcomeRef = new AtomicReference<>();

                // Áp dụng toàn bộ lô sự kiện VÀ nâng con trỏ sync trong CÙNG một transaction Room trên DISK.
                Boolean applied = runOnDisk(() -> session.runIfCurrent(sessionKey, () ->
                        db.runInTransaction(() -> {
                            SyncEventApplier.SyncBatchOutcome outcome = applier.applyBatchDetailed(myUserId, body.events);
                            db.syncStateDao().advance(myUserId, body.nextCursor, clockMs.getAsLong());
                            outcomeRef.set(outcome);
                        })
                ));

                if (!Boolean.TRUE.equals(applied)) {
                    return true;
                }

                SyncEventApplier.SyncBatchOutcome committedOutcome = outcomeRef.get();
                // Chỉ bắn thông báo incremental SAU KHI transaction Room đã commit và phiên vẫn còn hiện hành (D-05, D-08).
                BatchNotificationCallback cb = notificationCallback;
                if (cb != null && committedOutcome != null
                        && (!committedOutcome.newlyInsertedMessages.isEmpty() || !committedOutcome.missedCalls.isEmpty())
                        && sessionKey.equals(session.sessionKey())) {
                    cb.onBatchCommitted(sessionKey, myUserId, committedOutcome);
                }

                // Gửi biên nhận DELIVERED sau khi transaction đã commit chắc chắn xuống đĩa (D-11).
                Map<String, Long> deliveredTargets = committedOutcome != null
                        ? committedOutcome.highestIncomingByConversation
                        : Collections.emptyMap();
                sendDeliveredWatermarks(sessionKey, deliveredTargets);

                cursor = body.nextCursor;

                if (body.hasMore) {
                    continue;
                }

                if (body.retryAfterMs != null) {
                    // Server đang có giao dịch bị barrier giữ lại: giữ trạng thái chưa catch-up và đặt đúng 1 hẹn giờ.
                    notifyCatchUp(sessionKey, false);
                    scheduleBarrierRetry(sessionKey, Math.max(MIN_BARRIER_RETRY_MS, body.retryAfterMs));
                    return false;
                }

                notifyCatchUp(sessionKey, true);
                return true;
            }
            return true;
        } catch (Exception ignored) {
            notifyCatchUp(sessionKey, false);
            return false;
        }
    }

    /** Chạy /api/sync/bootstrap để khởi tạo snapshot; tuyệt đối không phát thông báo (D-05). */
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

    /** Trạng thái của một chu kỳ drain single-flight gắn với các phiên đã thực thi trong chu kỳ đó. */
    private static final class DrainState {
        final long id;
        @Nullable final String initiatorSessionKey;
        final Map<String, Boolean> resultBySession = new HashMap<>();
        boolean finished;
        boolean lastPassSucceeded = true;

        DrainState(long id, @Nullable String initiatorSessionKey) {
            this.id = id;
            this.initiatorSessionKey = initiatorSessionKey;
        }
    }
}
