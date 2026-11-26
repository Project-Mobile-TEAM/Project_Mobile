package com.utechat.data.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Chuẩn bị tệp trước khi upload (D-02, D-04): chạy trên luồng nền, KHÔNG gọi từ main thread.
 * - Ảnh: đọc chiều xoay EXIF, giải mã bằng inSampleSize, xoay/thu nhỏ để max(rộng, cao) &lt;= 1600px và nén lại JPEG
 *   (nén lại xóa luôn EXIF GPS) &lt;= 10MB.
 * - Video &lt;= 50MB và tài liệu &lt;= 25MB: sao chép sang filesDir/pending_media/ để sống sót khi quyền content:// của Photo Picker/SAF mất.
 * Mọi lỗi nghiệp vụ ném PrepareException mang mã lỗi trùng danh mục API (FILE_TOO_LARGE, UNSUPPORTED_TYPE...).
 */
public final class MediaPreparationHelper {
    public static final int MAX_IMAGE_DIMENSION = 1600;
    public static final long MAX_IMAGE_BYTES = 10_485_760L;
    public static final long MAX_VIDEO_BYTES = 52_428_800L;
    public static final long MAX_FILE_BYTES = 26_214_400L;

    private static final int JPEG_QUALITY_PRIMARY = 85;
    private static final int JPEG_QUALITY_FALLBACK = 70;
    private static final int MAX_FILE_NAME_LENGTH = 100;

    /** Tài liệu cho phép tải lên: phần mở rộng -> MIME chuẩn (khớp danh sách magic bytes ở backend). */
    private static final Map<String, String> ALLOWED_DOCUMENTS = new HashMap<>();
    static {
        ALLOWED_DOCUMENTS.put("pdf", "application/pdf");
        ALLOWED_DOCUMENTS.put("txt", "text/plain");
        ALLOWED_DOCUMENTS.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ALLOWED_DOCUMENTS.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        ALLOWED_DOCUMENTS.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        ALLOWED_DOCUMENTS.put("zip", "application/zip");
    }

    public MediaPreparationHelper() { }

    /** Kết quả chuẩn bị tệp: mô tả đầy đủ tệp riêng tư đã nằm trong filesDir/pending_media/. */
    public static final class PreparedMedia {
        @Nullable public final String clientId;
        @NonNull public final String kind;
        @NonNull public final String localPath;
        @NonNull public final String mime;
        public final long sizeBytes;
        @NonNull public final String fileName;
        @Nullable public final Integer width;
        @Nullable public final Integer height;
        @Nullable public final Integer durationMs;

        public PreparedMedia(
                @NonNull String kind,
                @NonNull String localPath,
                @NonNull String mime,
                long sizeBytes,
                @NonNull String fileName,
                @Nullable Integer width,
                @Nullable Integer height,
                @Nullable Integer durationMs) {
            this(extractClientIdFromPath(localPath), kind, localPath, mime, sizeBytes, fileName, width, height, durationMs);
        }

        public PreparedMedia(
                @Nullable String clientId,
                @NonNull String kind,
                @NonNull String localPath,
                @NonNull String mime,
                long sizeBytes,
                @NonNull String fileName,
                @Nullable Integer width,
                @Nullable Integer height,
                @Nullable Integer durationMs) {
            this.clientId = clientId;
            this.kind = kind;
            this.localPath = localPath;
            this.mime = mime;
            this.sizeBytes = sizeBytes;
            this.fileName = fileName;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
        }

        @Nullable
        private static String extractClientIdFromPath(@NonNull String path) {
            String name = new File(path).getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            return stem.matches("[A-Za-z0-9-]{1,64}") ? stem : null;
        }
    }

    /** Lỗi chuẩn bị tệp kèm mã lỗi ổn định (FILE_TOO_LARGE, UNSUPPORTED_TYPE, FILE_MISSING, IO_ERROR). */
    public static final class PrepareException extends Exception {
        @NonNull public final String code;

        public PrepareException(@NonNull String code, @Nullable Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public PrepareException(@NonNull String code) {
            this(code, null);
        }
    }

    /** Thư mục riêng tư chứa tệp chờ upload; không nằm trong cache nên không bị hệ điều hành dọn khi thiếu bộ nhớ. */
    @NonNull
    public static File pendingMediaDir(@NonNull Context context) {
        return new File(context.getFilesDir(), "pending_media");
    }

    /** Nén ảnh JPEG &lt;= 1600px, đúng chiều EXIF và &lt;= 10MB vào pending_media/&lt;clientId&gt;.jpg. */
    @NonNull
    public static PreparedMedia prepareImage(
            @NonNull Context context, @NonNull Uri sourceUri, @NonNull String clientId) throws PrepareException {
        validateClientId(clientId);
        ContentResolver resolver = context.getContentResolver();

        // Bước 1: chỉ đọc kích thước để chọn inSampleSize, tránh nạp ảnh gốc khổng lồ vào heap.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in == null) throw new PrepareException("FILE_MISSING");
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | SecurityException e) {
            throw new PrepareException("FILE_MISSING", e);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new PrepareException("UNSUPPORTED_TYPE");
        }

        // Bước 2: đọc chiều xoay EXIF.
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (IOException | RuntimeException ignored) {
            // Ảnh không có EXIF hợp lệ: coi như không xoay.
        }

        File dest = new File(ensurePendingDir(context), clientId + ".jpg");
        Bitmap decoded = null;
        Bitmap oriented = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateSampleSize(Math.max(bounds.outWidth, bounds.outHeight));
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = resolver.openInputStream(sourceUri)) {
                if (in == null) throw new PrepareException("FILE_MISSING");
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) throw new PrepareException("UNSUPPORTED_TYPE");

            Matrix matrix = matrixForOrientation(orientation);
            float scale = Math.min(1f, (float) MAX_IMAGE_DIMENSION
                    / Math.max(decoded.getWidth(), decoded.getHeight()));
            matrix.postScale(scale, scale);
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);

            writeJpeg(oriented, dest, JPEG_QUALITY_PRIMARY);
            if (dest.length() > MAX_IMAGE_BYTES) {
                writeJpeg(oriented, dest, JPEG_QUALITY_FALLBACK);
            }
            if (dest.length() > MAX_IMAGE_BYTES) {
                throw new PrepareException("FILE_TOO_LARGE");
            }
            String name = baseName(queryDisplayName(resolver, sourceUri), "photo") + ".jpg";
            return new PreparedMedia("IMAGE", dest.getAbsolutePath(), "image/jpeg", dest.length(),
                    name, oriented.getWidth(), oriented.getHeight(), null);
        } catch (PrepareException e) {
            deleteQuietly(dest);
            throw e;
        } catch (IOException | SecurityException e) {
            deleteQuietly(dest);
            throw new PrepareException("IO_ERROR", e);
        } catch (OutOfMemoryError e) {
            deleteQuietly(dest);
            throw new PrepareException("FILE_TOO_LARGE");
        } finally {
            if (oriented != null && oriented != decoded) oriented.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    /** Sao chép video &lt;= 50MB vào pending_media/ và đọc thời lượng, kích thước khung hình an toàn. */
    @NonNull
    public static PreparedMedia prepareVideo(
            @NonNull Context context, @NonNull Uri sourceUri, @NonNull String clientId) throws PrepareException {
        validateClientId(clientId);
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(sourceUri);
        if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw new PrepareException("UNSUPPORTED_TYPE");
        }
        String displayName = queryDisplayName(resolver, sourceUri);
        String ext = extensionOf(displayName);
        if (ext == null || !(ext.equals("mp4") || ext.equals("webm") || ext.equals("mov") || ext.equals("m4v"))) {
            ext = mime.toLowerCase(Locale.ROOT).contains("webm") ? "webm" : "mp4";
        }
        File dest = new File(ensurePendingDir(context), clientId + "." + ext);
        copyCapped(resolver, sourceUri, dest, MAX_VIDEO_BYTES);

        Integer width = null;
        Integer height = null;
        Integer durationMs = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(dest.getAbsolutePath());
            durationMs = parseIntOrNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            width = parseIntOrNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = parseIntOrNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        } catch (RuntimeException ignored) {
            // Không đọc được metadata vẫn gửi được; server tự kiểm tra magic bytes.
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // MediaMetadataRetriever.release() khai báo IOException trên compileSdk mới; bỏ qua lỗi giải phóng.
            }
        }
        String fileName = baseName(displayName, "video") + "." + ext;
        return new PreparedMedia("VIDEO", dest.getAbsolutePath(), mime, dest.length(),
                fileName, width, height, durationMs);
    }

    /** Sao chép tài liệu cho phép (pdf, txt, docx, xlsx, pptx, zip) &lt;= 25MB; từ chối apk và tệp thực thi. */
    @NonNull
    public static PreparedMedia prepareFile(
            @NonNull Context context, @NonNull Uri sourceUri, @NonNull String clientId) throws PrepareException {
        validateClientId(clientId);
        ContentResolver resolver = context.getContentResolver();
        String displayName = queryDisplayName(resolver, sourceUri);
        String rawProviderMime = resolver.getType(sourceUri);
        String providerMime = normalizeMime(rawProviderMime);
        String ext = extensionOf(displayName);
        String allowedMime = ext == null ? null : ALLOWED_DOCUMENTS.get(ext);
        if (allowedMime == null && ext == null && providerMime != null) {
            // Một số document provider SAF không gắn phần mở rộng trong DISPLAY_NAME: suy luận an toàn theo MIME cho phép.
            for (Map.Entry<String, String> entry : ALLOWED_DOCUMENTS.entrySet()) {
                if (entry.getValue().equals(providerMime)) {
                    ext = entry.getKey();
                    allowedMime = entry.getValue();
                    break;
                }
            }
            if (allowedMime == null && "application/x-zip-compressed".equals(providerMime)) {
                ext = "zip";
                allowedMime = "application/zip";
            }
        }
        if (allowedMime == null || ext == null) {
            throw new PrepareException("UNSUPPORTED_TYPE");
        }
        boolean zipAlias = "zip".equals(ext) && "application/x-zip-compressed".equals(providerMime);
        if (providerMime != null
                && !providerMime.equalsIgnoreCase(allowedMime)
                && !zipAlias
                && !providerMime.equalsIgnoreCase("application/octet-stream")
                && !ALLOWED_DOCUMENTS.containsValue(providerMime)) {
            throw new PrepareException("UNSUPPORTED_TYPE");
        }
        File dest = new File(ensurePendingDir(context), clientId + "." + ext);
        copyCapped(resolver, sourceUri, dest, MAX_FILE_BYTES);
        String fileName = baseName(displayName, "document") + "." + ext;
        return new PreparedMedia("FILE", dest.getAbsolutePath(), allowedMime, dest.length(),
                fileName, null, null, null);
    }

    @Nullable
    private static String normalizeMime(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        int semi = trimmed.indexOf(';');
        return (semi > 0 ? trimmed.substring(0, semi) : trimmed).trim();
    }

    /** Làm sạch tên tệp: bỏ đường dẫn, ký tự điều khiển và ký tự nguy hiểm; không bao giờ để lọt "../". */
    @NonNull
    public static String sanitizeFileName(@Nullable String raw) {
        if (raw == null) return "file";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return "file";
        return cleaned.length() > MAX_FILE_NAME_LENGTH ? cleaned.substring(0, MAX_FILE_NAME_LENGTH) : cleaned;
    }

    // ======================== HÀM NỘI BỘ ========================

    /** clientId chỉ được là UUID/ký tự an toàn để tên tệp không thể chứa đường dẫn lạ (chống path traversal). */
    private static void validateClientId(String clientId) throws PrepareException {
        if (clientId == null || !clientId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new PrepareException("VALIDATION");
        }
    }

    private static File ensurePendingDir(Context context) throws PrepareException {
        File dir = pendingMediaDir(context);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new PrepareException("IO_ERROR");
        }
        return dir;
    }

    private static void copyCapped(ContentResolver resolver, Uri source, File dest, long maxBytes)
            throws PrepareException {
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) throw new PrepareException("FILE_MISSING");
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new PrepareException("FILE_TOO_LARGE");
                    }
                    out.write(buffer, 0, read);
                }
            }
        } catch (PrepareException e) {
            deleteQuietly(dest);
            throw e;
        } catch (IOException | SecurityException e) {
            deleteQuietly(dest);
            throw new PrepareException("IO_ERROR", e);
        }
    }

    private static void writeJpeg(Bitmap bitmap, File dest, int quality) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("Không nén được JPEG");
            }
        }
    }

    private static int calculateSampleSize(int maxDimension) {
        int sample = 1;
        while (maxDimension / (sample * 2) >= MAX_IMAGE_DIMENSION) {
            sample *= 2;
        }
        return sample;
    }

    /** Ma trận biến đổi tương ứng 8 giá trị chiều EXIF chuẩn. */
    private static Matrix matrixForOrientation(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270f);
                break;
            default:
                break;
        }
        return matrix;
    }

    @Nullable
    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) {
            // Provider không hỗ trợ truy vấn tên: dùng tên mặc định.
        }
        return null;
    }

    @Nullable
    private static String extensionOf(@Nullable String displayName) {
        String name = sanitizeFileName(displayName);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(@Nullable String displayName, String fallback) {
        if (displayName == null) return fallback;
        String name = sanitizeFileName(displayName);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isEmpty() ? fallback : base;
    }

    @Nullable
    private static Integer parseIntOrNull(@Nullable String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
