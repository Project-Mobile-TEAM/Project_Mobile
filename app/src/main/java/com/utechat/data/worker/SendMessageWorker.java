package com.utechat.data.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.gson.Gson;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.di.Injection;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import retrofit2.Response;

/**
 * Worker gửi tin nhắn văn bản theo hàng đợi offline-first (D-05, D-14):
 * - Mỗi phiên đăng nhập dùng một chuỗi WorkManager tuần tự riêng "send-messages:<sessionKey>" với APPEND_OR_REPLACE.
 * - Input Data CHỈ chứa clientId (tuyệt đối không lưu nội dung tin nhắn hay JWT trong DB của WorkManager).
 * - Thử lại tối đa 3 lần cho lỗi mạng / 408 / 5xx / 429 (tôn trọng Retry-After); hết lượt hoặc gặp lỗi nghiệp vụ
 *   400/403/404/409 MEMBERSHIP_CHANGED sẽ đánh dấu FAILED và trả Result.success() để không làm kẹt các tin tiếp theo.
 * - Gặp 401 chỉ hủy chuỗi của đúng phiên xếp hàng, tuyệt đối không làm đăng xuất nhầm phiên đăng nhập mới.
 */
public class SendMessageWorker extends Worker {
    public static final String UNIQUE_WORK_PREFIX = "send-messages:";
    public static final String KEY_CLIENT_ID = "clientId";
    public static final String TAG_ALL = "send-messages";
    public static final String TAG_CLIENT_PREFIX = "client:";
    public static final String TAG_SESSION_PREFIX = "session:";
    public static final int MAX_AUTO_ATTEMPTS = 3;

    private static final long RETRY_SLEEP_SLICE_MS = 500L;
    private static final long MIN_RETRY_AFTER_SEC = 1L;
    private static final long MAX_RETRY_AFTER_SEC = 60L;
    private static final Gson GSON = new Gson();

    /** Trừu tượng hàm ngủ để bài kiểm thử JVM ghi nhận thời gian chờ Retry-After tức thì không cần sleep thật. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public SendMessageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Tạo tên hàng đợi WorkManager duy nhất theo sessionKey (generation:userId, không bao giờ chứa JWT). */
    @NonNull
    public static String queueName(@NonNull String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionKey không được rỗng");
        }
        return UNIQUE_WORK_PREFIX + sessionKey;
    }

    /** Đưa một tin nhắn vào cuối chuỗi gửi tuần tự của phiên hiện tại; chỉ chạy khi có mạng CONNECTED. */
    public static void enqueue(@NonNull WorkManager wm, @NonNull String clientId, @NonNull String sessionKey) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId không được rỗng");
        }
        String uniqueQueue = queueName(sessionKey);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(KEY_CLIENT_ID, clientId)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SendMessageWorker.class)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(TAG_ALL)
                .addTag(TAG_CLIENT_PREFIX + clientId)
                .addTag(TAG_SESSION_PREFIX + sessionKey)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        wm.enqueueUniqueWork(uniqueQueue, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    /** Trích xuất sessionKey lúc xếp hàng từ danh sách tag của WorkRequest. */
    @Nullable
    public static String extractSessionKey(@Nullable Set<String> tags) {
        if (tags == null) return null;
        for (String tag : tags) {
            if (tag != null && tag.startsWith(TAG_SESSION_PREFIX)) {
                return tag.substring(TAG_SESSION_PREFIX.length());
            }
        }
        return null;
    }

    @NonNull
    @Override
    public Result doWork() {
        String clientId = getInputData().getString(KEY_CLIENT_ID);
        String enqueuedSessionKey = extractSessionKey(getTags());
        Context appContext = getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(appContext);
        ChatApi api = ApiClient.getRetrofit().create(ChatApi.class);
        SessionManager session = SessionManager.get();
        WorkManager workManager = WorkManager.getInstance(appContext);
        return executeSend(
                db,
                api,
                session,
                workManager,
                Injection.getDiskExecutor(),
                Thread::sleep,
                clientId,
                enqueuedSessionKey,
                getRunAttemptCount(),
                this::isStopped);
    }

    /** Phiên bản rút gọn thuận tiện cho kiểm thử JVM với disk chạy đồng bộ. */
    @NonNull
    public static Result executeSend(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @Nullable SessionManager session,
            @Nullable WorkManager workManager,
            @NonNull Sleeper sleeper,
            @Nullable String clientId,
            @Nullable String enqueuedSessionKey,
            int attemptCount) {
        return executeSend(db, api, session, workManager, Runnable::run, sleeper, clientId, enqueuedSessionKey, attemptCount, () -> false);
    }

    /**
     * Lõi xử lý gửi một tin nhắn:
     * 1) Khóa đúng phiên xếp hàng & token của phiên đó.
     * 2) Kiểm tra tin còn ở SENDING, chưa có serverId, và với GROUP phải khớp mốc visibleFromMessageId.
     * 3) Gửi POST kèm Authorization cố định; phân nhánh chuẩn 200/201, 401, 400/403/404/409, và lỗi tạm thời.
     */
    @NonNull
    public static Result executeSend(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @Nullable SessionManager session,
            @Nullable WorkManager workManager,
            @NonNull Executor diskExecutor,
            @NonNull Sleeper sleeper,
            @Nullable String clientId,
            @Nullable String enqueuedSessionKey,
            int attemptCount,
            @NonNull BooleanSupplier isStopped) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return Result.success();
        }
        if (session == null || enqueuedSessionKey == null || enqueuedSessionKey.trim().isEmpty()) {
            return Result.success();
        }

        // Chụp nguyên tử token, meId, version của đúng phiên xếp hàng; nếu phiên đã đổi thì bỏ qua không gọi HTTP.
        final String token;
        final String meId;
        final String version;
        synchronized (session) {
            if (!enqueuedSessionKey.equals(session.sessionKey())) {
                return Result.success();
            }
            token = session.getToken();
            meId = session.getUserId();
            version = session.versionKey();
        }
        if (token == null || meId == null) {
            return Result.success();
        }

        // Đọc tin nhắn và kiểm tra tư cách thành viên nhóm dưới cùng khóa phiên trên luồng DISK.
        PreflightCheck preflight = runOnDisk(diskExecutor, () -> {
            PreflightCheck check = new PreflightCheck();
            boolean active = session.runIfCurrent(enqueuedSessionKey, () -> {
                MessageEntity msg = db.messageDao().findByClientId(clientId);
                if (msg == null
                        || !meId.equals(msg.getSenderId())
                        || msg.getServerId() != null
                        || !"SENDING".equals(msg.getStatus())) {
                    check.skip = true;
                    return;
                }
                ConversationEntity conv = db.conversationDao().getConversationSync(msg.getConversationId());
                if (conv != null && "GROUP".equals(conv.getType())) {
                    ConversationMemberEntity myMember = db.conversationDao().getMemberSync(msg.getConversationId(), meId);
                    if (myMember == null || myMember.getLeft()) {
                        db.runInTransaction(() -> { db.messageDao().markFailed(clientId, "NOT_A_MEMBER"); });
                        check.skip = true;
                        return;
                    }
                    Long queuedCutoff = msg.getQueuedVisibleFromMessageId();
                    long currentCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                    // Chặn gửi nhầm tin nhóm thuộc lần tham gia cũ hoặc dữ liệu nâng cấp từ v1 chưa có mốc (fail closed).
                    if (queuedCutoff == null || queuedCutoff.longValue() != currentCutoff) {
                        db.runInTransaction(() -> { db.messageDao().markFailed(clientId, "MEMBERSHIP_CHANGED"); });
                        check.skip = true;
                        return;
                    }
                }
                check.message = msg;
            });
            if (!active) {
                check.skip = true;
            }
            return check;
        });

        if (preflight.skip || preflight.message == null) {
            return Result.success();
        }
        MessageEntity msg = preflight.message;

        // Kiểm tra lại lần cuối trước khi mở kết nối HTTP để chắc chắn phiên chưa đổi.
        if (!enqueuedSessionKey.equals(session.sessionKey())) {
            return Result.success();
        }

        ChatDtos.SendMessageRequest body = new ChatDtos.SendMessageRequest(
                msg.getClientId(),
                "TEXT",
                msg.getText(),
                null,
                null,
                null,
                msg.getQueuedVisibleFromMessageId());

        Response<ChatDtos.MessageDto> response;
        try {
            response = api.sendMessage("Bearer " + token, msg.getConversationId(), body).execute();
        } catch (IOException e) {
            return handleTransientFailure(
                    db, session, diskExecutor, sleeper, isStopped,
                    enqueuedSessionKey, clientId, attemptCount, false, 0L);
        }

        int code = response.code();
        if (response.isSuccessful() && response.body() != null) {
            ChatDtos.MessageDto dto = response.body();
            long createdAt = SyncEventApplier.parseIsoMillis(dto.createdAt, msg.getCreatedAt());
            runOnDisk(diskExecutor, () -> {
                session.runIfCurrent(enqueuedSessionKey, () -> db.runInTransaction(() -> {
                    db.messageDao().markSentFromWorker(clientId, dto.id, createdAt);
                    db.conversationDao().updateLastMessageIfNewer(msg.getConversationId(), dto.id, createdAt);
                }));
                return null;
            });
            return Result.success();
        }

        if (code == 401) {
            // Chỉ hủy hàng đợi của phiên xếp hàng; chỉ xóa phiên và báo hết hạn nếu token/version vẫn là hiện tại.
            if (workManager != null) {
                workManager.cancelUniqueWork(queueName(enqueuedSessionKey));
            }
            synchronized (session) {
                if (session.clearIfCurrent(token, version)) {
                    SessionEvents.notifyExpired(session.versionKey());
                }
            }
            return Result.failure();
        }

        ParsedError parsedError = parseError(response);

        // Lỗi nghiệp vụ dứt điểm (400/403/404 hoặc 409 MEMBERSHIP_CHANGED): đánh dấu FAILED và trả success để không kẹt hàng đợi.
        if (code == 400
                || code == 403
                || code == 404
                || (code == 409 && "MEMBERSHIP_CHANGED".equals(parsedError.code))
                || (code >= 400 && code < 500 && code != 408 && code != 429)) {
            final String finalCode = parsedError.code;
            runOnDisk(diskExecutor, () -> {
                session.runIfCurrent(enqueuedSessionKey, () -> db.runInTransaction(() -> {
                    db.messageDao().markFailed(clientId, finalCode);
                    if ("NOT_A_MEMBER".equals(finalCode)) {
                        db.conversationDao().markMemberLeft(msg.getConversationId(), meId);
                    }
                }));
                return null;
            });
            return Result.success();
        }

        // Lỗi tạm thời: 408, 429 hoặc 5xx.
        boolean isRateLimited = (code == 429);
        long retryAfterSeconds = isRateLimited ? resolveRetryAfterSeconds(response, parsedError) : 0L;
        return handleTransientFailure(
                db, session, diskExecutor, sleeper, isStopped,
                enqueuedSessionKey, clientId, attemptCount, isRateLimited, retryAfterSeconds);
    }

    private static Result handleTransientFailure(
            AppDatabase db,
            SessionManager session,
            Executor diskExecutor,
            Sleeper sleeper,
            BooleanSupplier isStopped,
            String enqueuedSessionKey,
            String clientId,
            int attemptCount,
            boolean isRateLimited,
            long retryAfterSeconds) {
        if (attemptCount < MAX_AUTO_ATTEMPTS - 1) {
            if (isRateLimited && retryAfterSeconds > 0) {
                long remainingMs = retryAfterSeconds * 1_000L;
                while (remainingMs > 0) {
                    if (isStopped.getAsBoolean() || !Objects.equals(enqueuedSessionKey, session.sessionKey())) {
                        return Result.success();
                    }
                    long slice = Math.min(RETRY_SLEEP_SLICE_MS, remainingMs);
                    try {
                        sleeper.sleep(slice);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Result.retry();
                    }
                    remainingMs -= slice;
                }
            }
            if (!Objects.equals(enqueuedSessionKey, session.sessionKey())) {
                return Result.success();
            }
            return Result.retry();
        }

        // Đã hết 3 lần gửi tự động: đánh dấu FAILED (có khóa AND serverId IS NULL) và trả success để tin sau chạy tiếp.
        final String failCode = isRateLimited ? "RATE_LIMITED" : "NETWORK";
        runOnDisk(diskExecutor, () -> {
            session.runIfCurrent(enqueuedSessionKey, () -> db.runInTransaction(() -> {
                db.messageDao().markFailed(clientId, failCode);
            }));
            return null;
        });
        return Result.success();
    }

    private static long resolveRetryAfterSeconds(Response<?> response, ParsedError parsedError) {
        Long seconds = null;
        String header = response.headers().get("Retry-After");
        if (header != null && !header.trim().isEmpty()) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) { }
        }
        if (seconds == null && parsedError.retryAfterSeconds != null) {
            seconds = parsedError.retryAfterSeconds;
        }
        if (seconds == null) {
            seconds = MIN_RETRY_AFTER_SEC;
        }
        return Math.max(MIN_RETRY_AFTER_SEC, Math.min(MAX_RETRY_AFTER_SEC, seconds));
    }

    private static ParsedError parseError(Response<?> response) {
        String code = "HTTP_" + response.code();
        Long retryAfter = null;
        try {
            if (response.errorBody() != null) {
                String raw = response.errorBody().string();
                ChatDtos.ErrorBody body = GSON.fromJson(raw, ChatDtos.ErrorBody.class);
                if (body != null && body.error != null) {
                    if (body.error.code != null && !body.error.code.trim().isEmpty()) {
                        code = body.error.code;
                    }
                    if (body.error.details != null && body.error.details.retryAfterSeconds != null) {
                        retryAfter = body.error.details.retryAfterSeconds;
                    }
                }
            }
        } catch (Exception ignored) { }
        return new ParsedError(code, retryAfter);
    }

    private static <T> T runOnDisk(Executor diskExecutor, Callable<T> action) {
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class PreflightCheck {
        boolean skip;
        @Nullable MessageEntity message;
    }

    private static final class ParsedError {
        final String code;
        @Nullable final Long retryAfterSeconds;

        ParsedError(String code, @Nullable Long retryAfterSeconds) {
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
