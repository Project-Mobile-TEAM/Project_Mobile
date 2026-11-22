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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final long MAX_DOWNLOAD_BYTES = 52_428_800L;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final String ID_PATTERN = "[0-9a-fA-F-]{36}";

    private final Context appContext;
    private final OkHttpClient mediaClient;
    private final TransferDao transferDao;
    private final Executor networkExecutor;
    private final Executor diskExecutor;
    private final Executor mainExecutor;
    private final SessionManager session;
    private final ConcurrentHashMap<String, Set<Call>> activeCalls = new ConcurrentHashMap<>();

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
        networkExecutor.execute(() -> runDownload(sessionKey, token, attachmentId, relativeMediaUrl, fileName, callback));
    }

    /** Hủy mọi lượt tải đang chạy của phiên cũ và xóa tệp .part của phiên đó (gọi trước khi dọn dữ liệu phiên). */
    public void cancelDownloadsForSession(@NonNull String sessionKey) {
        Set<Call> calls = activeCalls.remove(sessionKey);
        if (calls != null) {
            for (Call call : calls) call.cancel();
        }
        deleteRecursively(partDir(sessionKey));
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
            String attachmentId,
            String relativeMediaUrl,
            @Nullable String fileName,
            Callback callback) {
        if (!sessionKey.equals(session.sessionKey())) return;
        if (attachmentId == null || !attachmentId.matches(ID_PATTERN)
                || relativeMediaUrl == null || !relativeMediaUrl.endsWith("/" + attachmentId)) {
            deliverError(sessionKey, callback, "INVALID_URL");
            return;
        }
        final String url;
        try {
            url = ApiClient.resolveBackendMediaUrl(BuildConfig.BACKEND_BASE_URL, relativeMediaUrl);
        } catch (IllegalArgumentException e) {
            deliverError(sessionKey, callback, "INVALID_URL");
            return;
        }

        File cacheDir = new File(appContext.getCacheDir(), "media_cache");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            deliverError(sessionKey, callback, "IO_ERROR");
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
            deliverSuccess(sessionKey, callback, target);
            return;
        }

        File partFile = new File(partDir(sessionKey), attachmentId + ".part");
        File parent = partFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            deliverError(sessionKey, callback, "IO_ERROR");
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

        boolean published = false;
        String errorCode = null;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                errorCode = mapHttpError(response.code());
            } else {
                streamToPart(response.body(), partFile, sessionKey, transferId);
                published = publish(sessionKey, partFile, target, transferId);
                if (!published) errorCode = "SESSION_CHANGED";
            }
        } catch (DownloadTooLargeException e) {
            errorCode = "FILE_TOO_LARGE";
        } catch (IOException e) {
            errorCode = call.isCanceled() ? "CANCELED" : "NETWORK";
        } finally {
            bucket.remove(call);
            if (!published && partFile.exists() && !partFile.delete()) partFile.deleteOnExit();
        }

        if (published) {
            deliverSuccess(sessionKey, callback, target);
        } else if (errorCode != null) {
            final String code = errorCode;
            diskExecutor.execute(() -> session.runIfCurrent(sessionKey, () ->
                    transferDao.updateState(transferId, "FAILED", 0, code, System.currentTimeMillis())));
            if (!"SESSION_CHANGED".equals(code) && !"CANCELED".equals(code)) {
                deliverError(sessionKey, callback, code);
            }
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

    /** Công bố nguyên tử: chỉ đổi tên .part thành tệp cache và ghi COMPLETED khi phiên còn hiện hành. */
    private boolean publish(String sessionKey, File partFile, File target, String transferId) {
        AtomicBoolean ok = new AtomicBoolean(false);
        onDisk(() -> session.runIfCurrent(sessionKey, () -> {
            try {
                Files.move(partFile.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                return;
            }
            transferDao.updateState(transferId, "COMPLETED", 100, null, System.currentTimeMillis());
            ok.set(true);
        }));
        return ok.get();
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
