package com.utechat.data.media;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import com.utechat.BuildConfig;
import com.utechat.data.local.dao.TransferDao;
import com.utechat.data.local.entity.AttachmentTransferEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.session.SessionManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Tải /api/media/{id} về cacheDir/media_cache/ bằng header Authorization: Bearer (D-03, DEPLOYMENT_SECURITY):
 * - Token và sessionKey được chụp CÙNG LÚC trước khi xếp hàng; không bao giờ đưa JWT cho ứng dụng ngoài.
 * - Ghi vào tệp .part riêng theo phiên, chỉ công bố tệp + hàng DOWNLOAD trong TransferDao khi phiên còn hiện hành.
 * - Chia sẻ sang ứng dụng ngoài bằng content:// của FileProvider với FLAG_GRANT_READ_URI_PERMISSION.
 */
public final class MediaDownloadManager {
    /** Kết quả tải; luôn được gọi trên luồng chính và chỉ khi phiên đăng nhập chưa đổi. */
    public interface Callback {
        void onSuccess(@NonNull File file);

        void onError(@NonNull String code);
    }

    private enum PublishResult {
        PUBLISHED,
        SESSION_CHANGED,
        IO_ERROR
    }

    private static final long MAX_DOWNLOAD_BYTES = 52_428_800L;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final String ID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final Context appContext;
    private final OkHttpClient mediaClient;
    private final TransferDao transferDao;
    private final Executor networkExecutor;
    private final Executor diskExecutor;
    private final Executor mainExecutor;
    private final SessionManager session;
    private final ConcurrentHashMap<String, Set<Call>> activeCalls = new ConcurrentHashMap<>();
    // Chống tải trùng đồng thời cùng 1 attachmentId trong cùng phiên (gom danh sách Callback để phát kết quả một lần).
    private final ConcurrentHashMap<String, List<Callback>> inFlightCallbacks = new ConcurrentHashMap<>();

    public MediaDownloadManager(
            @NonNull Context appContext,
            @NonNull OkHttpClient mediaOkHttpClient,
            @NonNull TransferDao transferDao,
            @NonNull Executor networkExecutor,
            @NonNull Executor diskExecutor,
            @NonNull Executor mainExecutor,
            @NonNull SessionManager session) {
        this.appContext = appContext.getApplicationContext();
        this.mediaClient = mediaOkHttpClient;
        this.transferDao = transferDao;
        this.networkExecutor = networkExecutor;
        this.diskExecutor = diskExecutor;
        this.mainExecutor = mainExecutor;
        this.session = session;
    }

    /** Tải tệp về cache (hoặc dùng lại bản đã tải của chính phiên này) rồi báo kết quả trên luồng chính. */
    public void downloadToCache(
            @NonNull String attachmentId,
            @NonNull String relativeMediaUrl,
            @Nullable String fileName,
            @Nullable String mime,
            @NonNull Callback callback) {
        final String sessionKey;
        final String token;
        synchronized (session) {
            sessionKey = session.sessionKey();
            token = session.getToken();
        }
        if (sessionKey == null || token == null) {
            mainExecutor.execute(() -> callback.onError("UNAUTHENTICATED"));
            return;
        }
        String dedupKey = sessionKey + ":" + attachmentId;
        final boolean[] shouldStart = {false};
        inFlightCallbacks.compute(dedupKey, (k, existing) -> {
            if (existing != null) {
                existing.add(callback);
                return existing;
            }
            shouldStart[0] = true;
            List<Callback> list = new ArrayList<>();
            list.add(callback);
            return list;
        });
        if (!shouldStart[0]) {
            return;
        }
        networkExecutor.execute(() -> runDownload(sessionKey, token, dedupKey, attachmentId, relativeMediaUrl, fileName, mime));
    }

    /** Hủy mọi lượt tải đang chạy của phiên cũ và xóa tệp .part trên diskExecutor (không chặn main thread). */
    public void cancelDownloadsForSession(@NonNull String sessionKey) {
        Set<Call> calls = activeCalls.remove(sessionKey);
        if (calls != null) {
            for (Call call : calls) call.cancel();
        }
        String prefix = sessionKey + ":";
        inFlightCallbacks.keySet().removeIf(k -> k.startsWith(prefix));
        diskExecutor.execute(() -> deleteRecursively(partDir(sessionKey)));
    }

    /** Intent mở tệp đã tải bằng ứng dụng ngoài qua FileProvider (không chứa token). */
    @NonNull
    public static Intent buildExternalViewIntent(
            @NonNull Context context, @NonNull File cachedFile, @Nullable String mime) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", cachedFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime != null && !mime.isEmpty() ? mime : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    // ======================== NỘI BỘ ========================

    private void runDownload(
            String sessionKey,
            String token,
            String dedupKey,
            String attachmentId,
            String relativeMediaUrl,
            @Nullable String fileName,
            @Nullable String mime) {
        if (!sessionKey.equals(session.sessionKey())) {
            inFlightCallbacks.remove(dedupKey);
            return;
        }
        if (attachmentId == null || !attachmentId.matches(ID_PATTERN)
                || !("/api/media/" + attachmentId).equals(relativeMediaUrl)) {
            finishWithError(sessionKey, dedupKey, "INVALID_URL");
            return;
        }
        final String url;
        try {
            url = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, relativeMediaUrl);
        } catch (IllegalArgumentException e) {
            finishWithError(sessionKey, dedupKey, "INVALID_URL");
            return;
        }

        File cacheDir = new File(appContext.getCacheDir(), "media_cache");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            finishWithError(sessionKey, dedupKey, "IO_ERROR");
            return;
        }
        File target = new File(cacheDir, attachmentId + "_" + MediaPreparationHelper.sanitizeFileName(fileName));
        String transferId = "dl:" + attachmentId;

        // Chỉ dùng lại tệp cache khi Room của phiên hiện tại ghi nhận đã tải xong (Room bị xóa khi đổi tài khoản).
        Boolean cached = onDisk(() -> {
            AttachmentTransferEntity row = transferDao.findByClientIdSync(transferId);
            return row != null && "COMPLETED".equals(row.getState()) && target.isFile() && target.length() > 0L;
        });
        if (Boolean.TRUE.equals(cached)) {
            finishWithSuccess(sessionKey, dedupKey, target);
            return;
        }

        // Đặt tên .part kèm nonce duy nhất để tuyệt đối không xung đột ghi đè dù có hủy/chạy lại.
        File partFile = new File(partDir(sessionKey), attachmentId + "_" + UUID.randomUUID() + ".part");
        File parent = partFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            finishWithError(sessionKey, dedupKey, "IO_ERROR");
            return;
        }

        long now = System.currentTimeMillis();
        onDisk(() -> session.runIfCurrent(sessionKey, () -> {
            AttachmentTransferEntity entity = new AttachmentTransferEntity();
            entity.setClientId(transferId);
            entity.setLocalUri("");
            entity.setState("DOWNLOADING");
            entity.setProgress(0);
            entity.setMediaUrl(relativeMediaUrl);
            entity.setDirection("DOWNLOAD");
            entity.setLocalPath(target.getAbsolutePath());
            entity.setFileName(fileName);
            entity.setMime(mime);
            entity.setUpdatedAt(now);
            transferDao.insert(entity);
        }));

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        Call call = mediaClient.newCall(request);
        Set<Call> bucket = activeCalls.computeIfAbsent(sessionKey, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        bucket.add(call);
        if (!sessionKey.equals(session.sessionKey())) {
            call.cancel();
            bucket.remove(call);
            activeCalls.computeIfPresent(sessionKey, (k, set) -> set.isEmpty() ? null : set);
            inFlightCallbacks.remove(dedupKey);
            if (partFile.exists() && !partFile.delete()) partFile.deleteOnExit();
            return;
        }

        boolean published = false;
        String errorCode = null;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                errorCode = mapHttpError(response.code());
            } else {
                streamToPart(response.body(), partFile, sessionKey, transferId);
                PublishResult result = publish(sessionKey, partFile, target, transferId);
                if (result == PublishResult.PUBLISHED) {
                    published = true;
                } else if (result == PublishResult.IO_ERROR) {
                    errorCode = "IO_ERROR";
                } else {
                    errorCode = "SESSION_CHANGED";
                }
            }
        } catch (DownloadTooLargeException e) {
            errorCode = "FILE_TOO_LARGE";
        } catch (IOException e) {
            errorCode = call.isCanceled() ? "CANCELED" : "NETWORK";
        } finally {
            bucket.remove(call);
            activeCalls.computeIfPresent(sessionKey, (k, set) -> set.isEmpty() ? null : set);
            if (!published && partFile.exists() && !partFile.delete()) partFile.deleteOnExit();
        }

        if (published) {
            finishWithSuccess(sessionKey, dedupKey, target);
        } else if (errorCode != null) {
            final String code = errorCode;
            diskExecutor.execute(() -> session.runIfCurrent(sessionKey, () ->
                    transferDao.updateState(transferId, "FAILED", 0, code, System.currentTimeMillis())));
            if (!"SESSION_CHANGED".equals(code) && !"CANCELED".equals(code)) {
                finishWithError(sessionKey, dedupKey, code);
            } else {
                inFlightCallbacks.remove(dedupKey);
            }
        } else {
            inFlightCallbacks.remove(dedupKey);
        }
    }

    private void streamToPart(ResponseBody body, File partFile, String sessionKey, String transferId)
            throws IOException, DownloadTooLargeException {
        long total = body.contentLength();
        if (total > MAX_DOWNLOAD_BYTES) throw new DownloadTooLargeException();
        long written = 0L;
        long lastProgressAt = 0L;
        int lastPercent = -1;
        try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(partFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > MAX_DOWNLOAD_BYTES) throw new DownloadTooLargeException();
                out.write(buffer, 0, read);
                if (total > 0L) {
                    int percent = (int) Math.min(99L, written * 100L / total);
                    long now = System.currentTimeMillis();
                    if (percent >= lastPercent + 5 && now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        lastPercent = percent;
                        lastProgressAt = now;
                        final int pct = percent;
                        // Tiến độ của phiên cũ bị bỏ khi runIfCurrent trả false.
                        diskExecutor.execute(() -> session.runIfCurrent(sessionKey, () ->
                                transferDao.updateProgress(transferId, "DOWNLOADING", pct, System.currentTimeMillis())));
                    }
                }
            }
        }
    }

    /** Công bố nguyên tử: phân biệt rõ lỗi đĩa IO_ERROR với SESSION_CHANGED để UI không bị treo spinner. */
    private PublishResult publish(String sessionKey, File partFile, File target, String transferId) {
        AtomicReference<PublishResult> outcome = new AtomicReference<>(PublishResult.SESSION_CHANGED);
        onDisk(() -> {
            boolean ran = session.runIfCurrent(sessionKey, () -> {
                try {
                    try {
                        Files.move(partFile.toPath(), target.toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    outcome.set(PublishResult.IO_ERROR);
                    return;
                }
                transferDao.updateState(transferId, "COMPLETED", 100, null, System.currentTimeMillis());
                outcome.set(PublishResult.PUBLISHED);
            });
            if (!ran) {
                outcome.set(PublishResult.SESSION_CHANGED);
            }
            return null;
        });
        return outcome.get();
    }

    private void finishWithSuccess(String sessionKey, String dedupKey, File file) {
        List<Callback> callbacks = drainCallbacks(dedupKey);
        for (Callback cb : callbacks) {
            deliverSuccess(sessionKey, cb, file);
        }
    }

    private void finishWithError(String sessionKey, String dedupKey, String code) {
        List<Callback> callbacks = drainCallbacks(dedupKey);
        for (Callback cb : callbacks) {
            deliverError(sessionKey, cb, code);
        }
    }

    private List<Callback> drainCallbacks(String dedupKey) {
        final List<Callback> drained = new ArrayList<>();
        inFlightCallbacks.computeIfPresent(dedupKey, (k, list) -> {
            drained.addAll(list);
            return null;
        });
        return drained;
    }

    private void deliverSuccess(String sessionKey, Callback callback, File file) {
        // Kiểm tra lại phiên trên main: phiên đã đổi thì bỏ qua, không trả URI cho người dùng mới.
        mainExecutor.execute(() -> {
            if (sessionKey.equals(session.sessionKey())) callback.onSuccess(file);
        });
    }

    private void deliverError(String sessionKey, Callback callback, String code) {
        mainExecutor.execute(() -> {
            if (sessionKey.equals(session.sessionKey())) callback.onError(code);
        });
    }

    private File partDir(String sessionKey) {
        String safe = sessionKey.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(appContext.getCacheDir(), "media_cache/.partial/" + safe);
    }

    private static String mapHttpError(int code) {
        switch (code) {
            case 401: return "UNAUTHORIZED";
            case 403: return "FORBIDDEN";
            case 404: return "NOT_FOUND";
            default: return "NETWORK";
        }
    }

    private <T> T onDisk(Callable<T> action) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }

    private static final class DownloadTooLargeException extends Exception {
    }
}
