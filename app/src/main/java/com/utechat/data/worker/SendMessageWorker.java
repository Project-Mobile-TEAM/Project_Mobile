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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.remote.api.ChatApi;
import com.utechat.data.remote.api.MediaApi;
import com.utechat.data.remote.model.ChatDtos;
import com.utechat.data.remote.model.MediaDtos;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import com.utechat.data.sync.SyncEventApplier;
import com.utechat.di.Injection;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import retrofit2.Response;

/**
 * Worker gửi tin nhắn theo hàng đợi offline-first (D-05, D-14, Phase 6 D-04):
 * - Mỗi phiên đăng nhập dùng một chuỗi WorkManager tuần tự riêng "send-messages:&lt;sessionKey&gt;" với APPEND_OR_REPLACE.
 * - Input Data CHỈ chứa clientId (tuyệt đối không lưu nội dung tin nhắn hay JWT trong DB của WorkManager).
 * - TEXT: gửi thẳng. LOCATION: gửi tọa độ đọc từ attachmentJson.
 * - IMAGE/AUDIO/VIDEO/FILE gửi 2 pha: (1) upload tệp rồi LƯU NGAY mediaUrl vào Room, (2) mới POST tin nhắn.
 *   Nếu mất mạng sau pha 1, lần thử lại bỏ qua upload và dùng mediaUrl đã lưu (không upload trùng).
 * - Thử lại tối đa 3 lần cho lỗi mạng / 408 / 5xx / 429 (tôn trọng Retry-After); hết lượt hoặc gặp lỗi nghiệp vụ
 *   400/403/404/413/415/409 MEMBERSHIP_CHANGED sẽ đánh dấu FAILED và trả Result.success() để không làm kẹt các tin tiếp theo.
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
    private static final long PROGRESS_MIN_INTERVAL_MS = 250L;
    private static final int PROGRESS_MIN_STEP_PERCENT = 5;
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
        // Bảo đảm hook dọn phiên trên SessionManager luôn được gắn ngay cả khi tiến trình chỉ thức dậy chạy Worker nền.
        Injection.provideAuthRepository(appContext);
        AppDatabase db = AppDatabase.getInstance(appContext);
        ChatApi api = ApiClient.getRetrofit().create(ChatApi.class);
        // Upload dùng Retrofit media với timeout dài 60s/120s thay vì timeout 15s của Retrofit thường.
        MediaApi mediaApi = ApiClient.getMediaRetrofit().create(MediaApi.class);
        SessionManager session = SessionManager.get();
        WorkManager workManager = WorkManager.getInstance(appContext);
        return executeSend(
                db,
                api,
                mediaApi,
                session,
                workManager,
                Injection.getDiskExecutor(),
                Thread::sleep,
                clientId,
                enqueuedSessionKey,
                getRunAttemptCount(),
                this::isStopped);
    }

    /** Phiên bản rút gọn thuận tiện cho kiểm thử JVM Phase 5 với disk chạy đồng bộ và không có MediaApi. */
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
        return executeSend(db, api, null, session, workManager, Runnable::run, sleeper, clientId,
                enqueuedSessionKey, attemptCount, () -> false);
    }

    /** Phiên bản 10 tham số của Phase 5, giữ nguyên chữ ký và ủy quyền cho bản có MediaApi = null. */
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
        return executeSend(db, api, null, session, workManager, diskExecutor, sleeper, clientId,
                enqueuedSessionKey, attemptCount, isStopped);
    }

    /**
     * Lõi xử lý gửi một tin nhắn:
     * 1) Khóa đúng phiên xếp hàng và token của phiên đó.
     * 2) Kiểm tra tin còn ở SENDING, chưa có serverId, và với GROUP phải khớp mốc visibleFromMessageId.
     * 3) Phân nhánh theo loại tin (TEXT / LOCATION / media 2 pha) rồi gửi POST kèm Authorization cố định;
     *    phân nhánh chuẩn 200/201, 401, 400/403/404/409 và lỗi tạm thời.
     */
    @NonNull
    public static Result executeSend(
            @NonNull AppDatabase db,
            @NonNull ChatApi api,
            @Nullable MediaApi mediaApi,
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
                        db.runInTransaction(() -> {
                            db.messageDao().markFailed(clientId, "NOT_A_MEMBER");
                            if (db.transferDao() != null) {
                                db.transferDao().updateState(clientId, "FAILED", 0, "NOT_A_MEMBER", System.currentTimeMillis());
                            }
                        });
                        check.skip = true;
                        return;
                    }
                    Long queuedCutoff = msg.getQueuedVisibleFromMessageId();
                    long currentCutoff = myMember.getVisibleFromMessageId() != null ? myMember.getVisibleFromMessageId() : 0L;
                    // Chặn gửi nhầm tin nhóm thuộc lần tham gia cũ hoặc dữ liệu nâng cấp từ v1 chưa có mốc (fail closed).
                    if (queuedCutoff == null || queuedCutoff.longValue() != currentCutoff) {
                        db.runInTransaction(() -> {
                            db.messageDao().markFailed(clientId, "MEMBERSHIP_CHANGED");
                            if (db.transferDao() != null) {
                                db.transferDao().updateState(clientId, "FAILED", 0, "MEMBERSHIP_CHANGED", System.currentTimeMillis());
                            }
                        });
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

        SendContext c = new SendContext(db, api, mediaApi, session, workManager, diskExecutor, sleeper, isStopped,
                clientId, enqueuedSessionKey, attemptCount, token, meId, version, msg);

        String type = msg.getType() != null ? msg.getType() : "TEXT";
        switch (type) {
            case "IMAGE":
            case "AUDIO":
            case "VIDEO":
            case "FILE":
                return executeMediaSend(c, type);
            case "LOCATION":
                return executeLocationSend(c);
            case "STICKER":
                return executeStickerSend(c);
            default:
                break;
        }

        ChatDtos.SendMessageRequest body = new ChatDtos.SendMessageRequest(
                msg.getClientId(),
                "TEXT",
                msg.getText(),
                null,
                null,
                null,
                msg.getQueuedVisibleFromMessageId());
        return sendAndHandle(c, body, null);
    }

    // ======================== LOCATION ========================

    /** Gửi tin vị trí: đọc latitude/longitude từ attachmentJson, không đụng tới MediaApi. */
    private static Result executeLocationSend(SendContext c) {
        Double lat = null;
        Double lng = null;
        try {
            String json = c.msg.getAttachmentJson();
            if (json != null) {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("latitude") && !obj.get("latitude").isJsonNull()) lat = obj.get("latitude").getAsDouble();
                if (obj.has("longitude") && !obj.get("longitude").isJsonNull()) lng = obj.get("longitude").getAsDouble();
            }
        } catch (RuntimeException ignored) {
            lat = null;
            lng = null;
        }
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            failMessageOnly(c, "VALIDATION");
            return Result.success();
        }
        ChatDtos.SendMessageRequest body = new ChatDtos.SendMessageRequest(
                c.clientId, "LOCATION", c.msg.getText(), null, lat, lng, c.msg.getQueuedVisibleFromMessageId());
        return sendAndHandle(c, body, null);
    }

    /** Nhãn dán nằm sẵn trong danh mục, Worker chỉ gửi ID và giữ nguyên clientId khi thử lại. */
    private static Result executeStickerSend(SendContext c) {
        String stickerId;
        try {
            JsonObject metadata = JsonParser.parseString(c.msg.getAttachmentJson()).getAsJsonObject();
            stickerId = metadata.get("stickerId").getAsString();
            java.util.UUID.fromString(stickerId);
        } catch (RuntimeException malformed) {
            failMessageOnly(c, "VALIDATION");
            return Result.success();
        }
        ChatDtos.SendMessageRequest body = new ChatDtos.SendMessageRequest(
                c.clientId, "STICKER", null, null, null, null, c.msg.getQueuedVisibleFromMessageId(), stickerId);
        return sendAndHandle(c, body, null);
    }

    // ======================== MEDIA 2 PHA (D-04) ========================

    /** Pha 1: upload (bỏ qua nếu đã có mediaUrl) + lưu mediaUrl ngay; Pha 2: POST tin nhắn với mediaUrl đã lưu. */
    private static Result executeMediaSend(SendContext c, String type) {
        AttachmentTransferEntity transfer = readTransfer(c);
        if (transfer == null) {
            failMessageOnly(c, "FILE_MISSING");
            return Result.success();
        }

        String mediaUrl = transfer.getMediaUrl();
        final String localPath = transfer.getLocalPath();

        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
            // ---------- Pha 1: upload ----------
            File file = localPath == null ? null : new File(localPath);
            if (file == null || !file.isFile() || !file.canRead()) {
                failBoth(c, "FILE_MISSING", 0);
                return Result.success();
            }
            if (c.mediaApi == null) {
                failBoth(c, "VALIDATION", 0);
                return Result.success();
            }

            MediaType contentType = MediaType.parse(
                    transfer.getMime() != null ? transfer.getMime() : "application/octet-stream");
            RequestBody countingBody = new CountingRequestBody(file, contentType, percent -> publishProgress(c, percent));
            String uploadName = safeUploadName(transfer.getFileName(), file.getName());
            // clientId của tin nhắn dùng cho mọi lần upload: mất phản hồi rồi gửi lại vẫn nhận đúng URL cũ (idempotent).
            RequestBody clientIdPart = RequestBody.create(c.clientId, MediaType.parse("text/plain"));
            MultipartBody.Part kindPart = MultipartBody.Part.createFormData("kind", type);
            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", uploadName, countingBody);
            RequestBody durationPart = transfer.getDurationMs() == null
                    ? null
                    : RequestBody.create(String.valueOf(transfer.getDurationMs()), MediaType.parse("text/plain"));

            Response<MediaDtos.MediaUploadResponse> up;
            try {
                up = c.mediaApi.uploadMedia("Bearer " + c.token, clientIdPart, kindPart, filePart, durationPart).execute();
            } catch (IOException e) {
                return handleTransientFailure(c, false, 0L, code -> markTransferFailed(c, code, 0));
            }

            int code = up.code();
            MediaDtos.MediaUploadResponse uploaded = up.body();
            if (up.isSuccessful() && uploaded != null && uploaded.mediaUrl != null
                    && uploaded.mediaUrl.startsWith("/api/media/")) {
                mediaUrl = uploaded.mediaUrl;
                if (!persistUploadResult(c, uploaded)) {
                    return Result.success();
                }
            } else if (code == 401) {
                return handleUnauthorized(c);
            } else if (up.isSuccessful()) {
                // Phản hồi 2xx nhưng thiếu/sai mediaUrl: không thể gửi tin tiếp, đánh dấu lỗi dứt điểm.
                failBoth(c, "INTERNAL", 0);
                return Result.success();
            } else {
                ParsedError parsed = parseError(up);
                boolean permanent = code >= 400 && code < 500 && code != 408 && code != 429;
                if (permanent) {
                    String errorCode = parsed.code;
                    if (errorCode.startsWith("HTTP_")) {
                        if (code == 413) errorCode = "FILE_TOO_LARGE";
                        else if (code == 415) errorCode = "UNSUPPORTED_TYPE";
                    }
                    failBoth(c, errorCode, 0);
                    // 413/415: tệp không bao giờ gửi được nên xóa luôn bản tạm; lỗi khác giữ lại để người dùng thử lại.
                    if (code == 413 || code == 415) {
                        deleteLocalFile(c, localPath);
                    }
                    return Result.success();
                }
                boolean rateLimited = code == 429;
                long retryAfter = rateLimited ? resolveRetryAfterSeconds(up, parsed) : 0L;
                return handleTransientFailure(c, rateLimited, retryAfter, failCode -> markTransferFailed(c, failCode, 0));
            }
        }

        // ---------- Pha 2: gửi tin nhắn với mediaUrl đã được lưu ----------
        ChatDtos.SendMessageRequest body = new ChatDtos.SendMessageRequest(
                c.clientId, type, c.msg.getText(), mediaUrl, null, null, c.msg.getQueuedVisibleFromMessageId());
        return sendAndHandle(c, body, new MediaFinish(localPath));
    }

    /** Lưu mediaUrl + metadata vào TransferDao và attachmentJson trong cùng transaction TRƯỚC khi sang pha 2. */
    private static boolean persistUploadResult(SendContext c, MediaDtos.MediaUploadResponse uploaded) {
        final String updatedJson = mergeAttachmentJson(c.msg.getAttachmentJson(), uploaded);
        final long now = System.currentTimeMillis();
        return Boolean.TRUE.equals(runOnDisk(c.diskExecutor, () -> c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
            c.db.transferDao().markUploaded(
                    c.clientId, uploaded.mediaUrl, uploaded.mime, uploaded.sizeBytes, uploaded.durationMs,
                    uploaded.width, uploaded.height, uploaded.fileName, now);
            c.db.messageDao().updateAttachmentJson(c.msg.getLocalId(), updatedJson);
        }))));
    }

    private static String mergeAttachmentJson(@Nullable String existing, MediaDtos.MediaUploadResponse uploaded) {
        JsonObject root = new JsonObject();
        if (existing != null) {
            try {
                JsonElement parsed = JsonParser.parseString(existing);
                if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (RuntimeException ignored) {
                root = new JsonObject();
            }
        }
        if (uploaded.mediaUrl != null) root.addProperty("mediaUrl", uploaded.mediaUrl);
        if (uploaded.mime != null) root.addProperty("mime", uploaded.mime);
        if (uploaded.sizeBytes != null) root.addProperty("sizeBytes", uploaded.sizeBytes);
        if (uploaded.fileName != null) root.addProperty("fileName", uploaded.fileName);
        if (uploaded.durationMs != null) root.addProperty("durationMs", uploaded.durationMs);
        if (uploaded.width != null) root.addProperty("width", uploaded.width);
        if (uploaded.height != null) root.addProperty("height", uploaded.height);
        return GSON.toJson(root);
    }

    @Nullable
    private static AttachmentTransferEntity readTransfer(SendContext c) {
        return runOnDisk(c.diskExecutor, () -> {
            AtomicReference<AttachmentTransferEntity> ref = new AtomicReference<>();
            c.session.runIfCurrent(c.key, () -> ref.set(c.db.transferDao().findByClientIdSync(c.clientId)));
            return ref.get();
        });
    }

    /** Đánh dấu tin FAILED và hàng transfer FAILED với mã lỗi bền vững (giữ nguyên mediaUrl nếu đã có). */
    private static void failBoth(SendContext c, String errorCode, int progress) {
        final long now = System.currentTimeMillis();
        runOnDisk(c.diskExecutor, () -> {
            c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
                c.db.messageDao().markFailed(c.clientId, errorCode);
                c.db.transferDao().updateState(c.clientId, "FAILED", progress, errorCode, now);
            }));
            return null;
        });
    }

    private static void failMessageOnly(SendContext c, String errorCode) {
        runOnDisk(c.diskExecutor, () -> {
            c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
                c.db.messageDao().markFailed(c.clientId, errorCode);
            }));
            return null;
        });
    }

    /** Dùng khi hết lượt thử tự động: markFailed của tin đã nằm trong handleTransientFailure, ở đây chỉ cập nhật transfer. */
    private static void markTransferFailed(SendContext c, String errorCode, int progress) {
        final long now = System.currentTimeMillis();
        c.db.transferDao().updateState(c.clientId, "FAILED", progress, errorCode, now);
    }

    private static void deleteLocalFile(SendContext c, @Nullable String localPath) {
        if (localPath == null) return;
        runOnDisk(c.diskExecutor, () -> {
            File file = new File(localPath);
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
            return null;
        });
    }

    /** Ghi tiến độ upload không chặn luồng mạng; lỗi DB (phiên đã đổi) bị bỏ qua. */
    private static void publishProgress(SendContext c, int percent) {
        try {
            c.diskExecutor.execute(() -> {
                try {
                    c.session.runIfCurrent(c.key, () ->
                            c.db.transferDao().updateProgress(c.clientId, "UPLOADING", percent, System.currentTimeMillis()));
                } catch (RuntimeException ignored) {
                    // Phiên đã đổi hoặc DB đã đóng: không ảnh hưởng tiến trình upload.
                }
            });
        } catch (RuntimeException ignored) {
            // Executor đã đóng.
        }
    }

    private static String safeUploadName(@Nullable String preferred, String fallback) {
        String name = preferred != null && !preferred.trim().isEmpty() ? preferred : fallback;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "_").trim();
        return name.isEmpty() ? "upload" : name;
    }

    // ======================== GỬI TIN + XỬ LÝ PHẢN HỒI CHUNG ========================

    /**
     * Gửi POST tin nhắn rồi phân nhánh phản hồi; dùng chung cho TEXT, LOCATION và pha 2 của media.
     * Với media (finish != null) kết quả còn được phản chiếu vào bảng attachment_transfer.
     */
    private static Result sendAndHandle(SendContext c, ChatDtos.SendMessageRequest body, @Nullable MediaFinish finish) {
        Consumer<String> onExhausted = finish == null ? null : code -> markTransferFailed(c, code, 100);

        Response<ChatDtos.MessageDto> response;
        try {
            response = c.api.sendMessage("Bearer " + c.token, c.msg.getConversationId(), body).execute();
        } catch (IOException e) {
            return handleTransientFailure(c, false, 0L, onExhausted);
        }

        int code = response.code();
        if (response.isSuccessful() && response.body() != null) {
            ChatDtos.MessageDto dto = response.body();
            long createdAt = SyncEventApplier.parseIsoMillis(dto.createdAt, c.msg.getCreatedAt());
            runOnDisk(c.diskExecutor, () -> {
                c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
                    var messages = c.db.messageDao();
                    // ACK tới sau thao tác xóa ở thiết bị khác chỉ được dọn tin chờ cục bộ.
                    if (messages.isDeletedForMe(dto.id)) {
                        messages.deletePendingByClientId(c.clientId);
                        if (finish != null) c.db.transferDao().deleteByClientId(c.clientId);
                        return;
                    }
                    messages.markSentFromWorker(c.clientId, dto.id, createdAt);
                    if (dto.recalledAt != null) {
                        new SyncEventApplier(c.db).applyRecall(dto.id,
                                SyncEventApplier.parseIsoMillis(dto.recalledAt, createdAt));
                    }
                    c.db.conversationDao().updateLastMessageIfNewer(c.msg.getConversationId(), dto.id, createdAt);
                    if (finish != null) {
                        c.db.transferDao().updateState(c.clientId, "COMPLETED", 100, null, System.currentTimeMillis());
                    }
                }));
                return null;
            });
            // Server đã ack: xóa bản tạm trong pending_media/ (D-04).
            if (finish != null) {
                deleteLocalFile(c, finish.localPath);
            }
            return Result.success();
        }

        if (code == 401) {
            return handleUnauthorized(c);
        }

        ParsedError parsedError = parseError(response);

        // Lỗi nghiệp vụ dứt điểm (400/403/404 hoặc 409 MEMBERSHIP_CHANGED): đánh dấu FAILED và trả success để không kẹt hàng đợi.
        if (code == 400
                || code == 403
                || code == 404
                || (code == 409 && "MEMBERSHIP_CHANGED".equals(parsedError.code))
                || (code >= 400 && code < 500 && code != 408 && code != 429)) {
            final String finalCode = parsedError.code;
            runOnDisk(c.diskExecutor, () -> {
                c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
                    c.db.messageDao().markFailed(c.clientId, finalCode);
                    if (finish != null) {
                        // updateState không đụng tới mediaUrl nên lần thử lại thủ công bỏ qua POST /api/media.
                        c.db.transferDao().updateState(c.clientId, "FAILED", 100, finalCode, System.currentTimeMillis());
                    }
                    if ("NOT_A_MEMBER".equals(finalCode)) {
                        c.db.conversationDao().markMemberLeft(c.msg.getConversationId(), c.meId);
                    }
                }));
                return null;
            });
            return Result.success();
        }

        // Lỗi tạm thời: 408, 429 hoặc 5xx.
        boolean isRateLimited = (code == 429);
        long retryAfterSeconds = isRateLimited ? resolveRetryAfterSeconds(response, parsedError) : 0L;
        return handleTransientFailure(c, isRateLimited, retryAfterSeconds, onExhausted);
    }

    /** 401: chỉ hủy hàng đợi của phiên xếp hàng; chỉ xóa phiên và báo hết hạn nếu token/version vẫn là hiện tại. */
    private static Result handleUnauthorized(SendContext c) {
        if (c.workManager != null) {
            c.workManager.cancelUniqueWork(queueName(c.key));
        }
        synchronized (c.session) {
            if (c.session.clearIfCurrent(c.token, c.version)) {
                SessionEvents.notifyExpired(c.session.versionKey());
            }
        }
        return Result.failure();
    }

    private static Result handleTransientFailure(
            SendContext c,
            boolean isRateLimited,
            long retryAfterSeconds,
            @Nullable Consumer<String> onExhausted) {
        if (c.attemptCount < MAX_AUTO_ATTEMPTS - 1) {
            if (isRateLimited && retryAfterSeconds > 0) {
                long remainingMs = retryAfterSeconds * 1_000L;
                while (remainingMs > 0) {
                    if (c.isStopped.getAsBoolean() || !Objects.equals(c.key, c.session.sessionKey())) {
                        return Result.success();
                    }
                    long slice = Math.min(RETRY_SLEEP_SLICE_MS, remainingMs);
                    try {
                        c.sleeper.sleep(slice);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Result.retry();
                    }
                    remainingMs -= slice;
                }
            }
            if (!Objects.equals(c.key, c.session.sessionKey())) {
                return Result.success();
            }
            return Result.retry();
        }

        // Đã hết 3 lần gửi tự động: đánh dấu FAILED (có khóa AND serverId IS NULL) và trả success để tin sau chạy tiếp.
        final String failCode = isRateLimited ? "RATE_LIMITED" : "NETWORK";
        runOnDisk(c.diskExecutor, () -> {
            c.session.runIfCurrent(c.key, () -> c.db.runInTransaction(() -> {
                c.db.messageDao().markFailed(c.clientId, failCode);
                if (onExhausted != null) {
                    onExhausted.accept(failCode);
                }
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

    // ======================== LỚP PHỤ TRỢ ========================

    /** Gói gọn mọi tham số cố định của một lượt gửi để truyền giữa các hàm xử lý. */
    private static final class SendContext {
        final AppDatabase db;
        final ChatApi api;
        @Nullable final MediaApi mediaApi;
        final SessionManager session;
        @Nullable final WorkManager workManager;
        final Executor diskExecutor;
        final Sleeper sleeper;
        final BooleanSupplier isStopped;
        final String clientId;
        final String key;
        final int attemptCount;
        final String token;
        final String meId;
        final String version;
        final MessageEntity msg;

        SendContext(
                AppDatabase db, ChatApi api, @Nullable MediaApi mediaApi, SessionManager session,
                @Nullable WorkManager workManager, Executor diskExecutor, Sleeper sleeper,
                BooleanSupplier isStopped, String clientId, String key, int attemptCount,
                String token, String meId, String version, MessageEntity msg) {
            this.db = db;
            this.api = api;
            this.mediaApi = mediaApi;
            this.session = session;
            this.workManager = workManager;
            this.diskExecutor = diskExecutor;
            this.sleeper = sleeper;
            this.isStopped = isStopped;
            this.clientId = clientId;
            this.key = key;
            this.attemptCount = attemptCount;
            this.token = token;
            this.meId = meId;
            this.version = version;
            this.msg = msg;
        }
    }

    /** Thông tin cần để dọn dẹp sau khi server xác nhận tin media. */
    private static final class MediaFinish {
        @Nullable final String localPath;

        MediaFinish(@Nullable String localPath) {
            this.localPath = localPath;
        }
    }

    /** RequestBody bọc tệp để báo tiến độ upload (chỉ báo khi tăng >= 5% và cách nhau >= 250ms). */
    private static final class CountingRequestBody extends RequestBody {
        private final File file;
        @Nullable private final MediaType contentType;
        private final java.util.function.IntConsumer onProgress;

        CountingRequestBody(File file, @Nullable MediaType contentType, java.util.function.IntConsumer onProgress) {
            this.file = file;
            this.contentType = contentType;
            this.onProgress = onProgress;
        }

        @Nullable @Override public MediaType contentType() { return contentType; }

        @Override public long contentLength() { return file.length(); }

        @Override public void writeTo(@NonNull BufferedSink sink) throws IOException {
            long total = file.length();
            long written = 0L;
            long lastAt = 0L;
            int lastPercent = -PROGRESS_MIN_STEP_PERCENT;
            try (Source source = Okio.source(file)) {
                okio.Buffer buffer = new okio.Buffer();
                long read;
                while ((read = source.read(buffer, 8192L)) != -1L) {
                    sink.write(buffer, read);
                    written += read;
                    if (total > 0L) {
                        int percent = (int) Math.min(99L, written * 100L / total);
                        long now = System.currentTimeMillis();
                        if (percent >= lastPercent + PROGRESS_MIN_STEP_PERCENT && now - lastAt >= PROGRESS_MIN_INTERVAL_MS) {
                            lastPercent = percent;
                            lastAt = now;
                            onProgress.accept(percent);
                        }
                    }
                }
            }
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
