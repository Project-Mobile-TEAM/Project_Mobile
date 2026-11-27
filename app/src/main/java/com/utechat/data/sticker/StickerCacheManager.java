package com.utechat.data.sticker;

import android.content.Context;
import com.utechat.data.local.dao.StickerDao;
import com.utechat.data.local.entity.StickerEntity;
import com.utechat.data.remote.api.ApiClient;
import com.utechat.data.session.SessionEvents;
import com.utechat.data.session.SessionManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Lưu ảnh nhãn dán để chọn offline, chỉ công bố tệp nếu phiên tải vẫn còn hiệu lực. */
public final class StickerCacheManager {
    private static final Pattern SAFE_CODE = Pattern.compile("^[a-z0-9_-]{1,50}$");
    private static final long MAX_STICKER_BYTES = 1024 * 1024;

    private final Path directory;
    private final OkHttpClient client;
    private final String backendBaseUrl;
    private final StickerDao dao;
    private final SessionManager session;
    private final Executor ioExecutor;
    private final Executor diskExecutor;

    public StickerCacheManager(Context context, OkHttpClient client, String backendBaseUrl,
                               StickerDao dao, SessionManager session, Executor ioExecutor,
                               Executor diskExecutor) {
        this(new File(context.getFilesDir(), "stickers").toPath(), client, backendBaseUrl, dao,
                session, ioExecutor, diskExecutor);
    }

    StickerCacheManager(Path directory, OkHttpClient client, String backendBaseUrl, StickerDao dao,
                        SessionManager session, Executor ioExecutor, Executor diskExecutor) {
        this.directory = directory;
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.backendBaseUrl = backendBaseUrl;
        this.dao = dao;
        this.session = session;
        this.ioExecutor = ioExecutor;
        this.diskExecutor = diskExecutor;
    }

    /** Dùng cùng token với danh mục đã tải để request trễ không dùng phiên mới. */
    public void cachePackAsync(String sessionKey, String authorization, String packCode, List<StickerEntity> stickers) {
        if (packCode == null || !SAFE_CODE.matcher(packCode).matches() || stickers.isEmpty()) {
            return;
        }
        ioExecutor.execute(() -> {
            for (StickerEntity sticker : stickers) {
                if (!sessionKey.equals(session.sessionKey())) {
                    return;
                }
                cacheSticker(sessionKey, authorization, packCode, sticker);
            }
        });
    }

    private void cacheSticker(String sessionKey, String authorization, String packCode, StickerEntity sticker) {
        String code = sticker.getCode();
        String relativeUrl = sticker.getImageUrl();
        if (!SAFE_CODE.matcher(code).matches()) {
            return;
        }
        // Tên cục bộ phải khớp chính xác tên do backend phục vụ, không thể thoát thư mục bộ nhãn dán.
        String assetPrefix = "/api/stickers/assets/" + packCode + "/" + code;
        String extension = relativeUrl.endsWith(".webp") ? ".webp" : ".png";
        if (!relativeUrl.equals(assetPrefix + extension)) {
            return;
        }
        Path target = directory.resolve(packCode).resolve(code + extension);
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                diskExecutor.execute(() -> session.runIfCurrent(sessionKey, () -> {
                    if (Files.isRegularFile(target)) {
                        dao.updateStickerLocalPath(sticker.getStickerId(), target.toString());
                    }
                }));
                return;
            }
            String url = ApiClient.resolveBackendStickerUrl(backendBaseUrl, relativeUrl);
            Request request = new Request.Builder().url(url).header("Authorization", authorization).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 401 && authorization.startsWith("Bearer ")) {
                    // Chỉ xóa đúng phiên đã gửi token hết hạn, kể cả khi tải ảnh đến chậm.
                    session.runIfCurrent(sessionKey, () -> {
                        if (session.clearIfCurrent(authorization.substring(7), session.versionKey())) {
                            SessionEvents.notifyExpired(session.versionKey());
                        }
                    });
                    return;
                }
                ResponseBody body = response.body();
                String mime = extension.equals(".webp") ? "image/webp" : "image/png";
                if (!response.isSuccessful() || body == null || body.contentType() == null
                        || !mime.equals(body.contentType().type() + "/" + body.contentType().subtype())
                        || body.contentLength() > MAX_STICKER_BYTES
                        || body.source().request(MAX_STICKER_BYTES + 1)) {
                    return;
                }
                byte[] content = body.source().readByteArray();
                if (content.length != 0) {
                    diskExecutor.execute(() -> session.runIfCurrent(sessionKey,
                            () -> publish(sticker.getStickerId(), target, content)));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Giữ metadata và thử lại trong lần tải danh mục tiếp theo nếu mạng hoặc ổ đĩa gián đoạn.
        }
    }

    /** Cập nhật đường dẫn Room sau khi ảnh hoàn chỉnh đã được chuyển vào tên chính thức. */
    private void publish(String stickerId, Path target, byte[] content) {
        Path pending = null;
        try {
            Files.createDirectories(target.getParent());
            pending = Files.createTempFile(target.getParent(), "sticker-", ".part");
            Files.write(pending, content);
            try {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dao.updateStickerLocalPath(stickerId, target.toString());
        } catch (IOException ignored) {
            // Không ghi đường dẫn khi tệp đích chưa sẵn sàng.
        } finally {
            if (pending != null) {
                try {
                    Files.deleteIfExists(pending);
                } catch (IOException ignored) {
                    // Thư mục tạm sẽ được dọn khi phiên kết thúc.
                }
            }
        }
    }
}
