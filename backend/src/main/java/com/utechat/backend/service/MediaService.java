package com.utechat.backend.service;

import com.utechat.backend.dto.MediaDtos.MediaDownloadMeta;
import com.utechat.backend.dto.MediaDtos.MediaUploadResponse;
import com.utechat.backend.dto.MediaDtos.MediaUploadResult;
import com.utechat.backend.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Nghiệp vụ lưu trữ tệp tin đa phương tiện trên ổ đĩa server (MEDIA_STORAGE_DIR),
 * kiểm định tính hợp lệ của tệp qua magic bytes, chống path traversal, chống Zip-Bomb,
 * tách I/O stream 50MB ra khỏi DB transaction để không chiếm giữ connection HikariCP,
 * và kiểm soát quyền truy cập tải tệp theo thành viên hội thoại (D-01, D-04).
 */
@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private static final Set<String> ALLOWED_KINDS = Set.of("IMAGE", "AUDIO", "VIDEO", "FILE");
    private static final Set<String> ALLOWED_AUDIO_FTYP_BRANDS = Set.of(
            "M4A ", "M4B ", "mp42", "isom", "mp41", "dash", "3gp4", "3gp5");
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_HTML_SCAN_CHARS = 4096;
    private static final BigInteger MAX_INT_BIG = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000L);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final MediaUploadRateLimiter rateLimiter;
    private final Path rootDir;
    private final long maxImageBytes;
    private final long maxAudioBytes;
    private final long maxVideoBytes;
    private final long maxFileBytes;

    public MediaService(
            JdbcTemplate jdbc,
            Clock clock,
            TransactionTemplate transactionTemplate,
            MediaUploadRateLimiter rateLimiter,
            @Value("${app.media.storage-dir:./data/media}") String storageDirStr,
            @Value("${app.media.max-image-bytes:10485760}") long maxImageBytes,
            @Value("${app.media.max-audio-bytes:10485760}") long maxAudioBytes,
            @Value("${app.media.max-video-bytes:52428800}") long maxVideoBytes,
            @Value("${app.media.max-file-bytes:26214400}") long maxFileBytes) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.rateLimiter = rateLimiter;
        this.rootDir = Paths.get(storageDirStr).toAbsolutePath().normalize();
        this.maxImageBytes = maxImageBytes;
        this.maxAudioBytes = maxAudioBytes;
        this.maxVideoBytes = maxVideoBytes;
        this.maxFileBytes = maxFileBytes;

        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            log.error("Không thể khởi tạo thư mục lưu media: {}", rootDir, e);
        }
    }

    /**
     * Tải tệp lên server:
     * - Stream tới tệp tạm và kiểm tra magic bytes NGOÀI transaction DB để không chiếm connection HikariCP suốt 50MB.
     * - Mở transaction ngắn cho thao tác idempotent INSERT + công bố file nguyên tử + tự xóa file nếu rollback.
     */
    public MediaUploadResult storeUpload(
            UUID actorId,
            UUID clientId,
            String rawKind,
            MultipartFile file,
            Integer durationHintMs) {
        if (actorId == null || clientId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "actorId and clientId are required");
        }
        if (rawKind == null || !ALLOWED_KINDS.contains(rawKind.toUpperCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid media kind: " + rawKind);
        }
        String kind = rawKind.toUpperCase(Locale.ROOT);

        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "File must not be empty");
        }

        // 1. Kiểm tra upload idempotent theo (uploader_id, upload_client_id) trước để retry mạng không tốn quota rate limit.
        MediaUploadResponse replay = findExistingUpload(actorId, clientId, kind);
        if (replay != null) {
            return new MediaUploadResult(replay, false);
        }

        // 2. Kiểm tra giới hạn tần suất 20 upload / phút / userId cho upload mới.
        rateLimiter.checkAndRecord(actorId);

        long kindLimit = switch (kind) {
            case "IMAGE" -> maxImageBytes;
            case "AUDIO" -> maxAudioBytes;
            case "VIDEO" -> maxVideoBytes;
            case "FILE" -> maxFileBytes;
            default -> maxFileBytes;
        };

        Path tempDir = rootDir.resolve(".tmp");
        Path tempFile = null;
        try {
            Files.createDirectories(tempDir);
            tempFile = Files.createTempFile(tempDir, "upload_", ".bin");

            // 3. Stream tệp vào file tạm NGOÀI DB transaction; ngắt ngay nếu vượt ngưỡng cho loại tệp này.
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = file.getInputStream();
                 OutputStream out = Files.newOutputStream(tempFile)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > kindLimit) {
                        throw new ApiException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "FILE_TOO_LARGE",
                                "File exceeds maximum size allowed for " + kind);
                    }
                    out.write(buffer, 0, read);
                }
            }

            if (totalBytes <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "File is empty");
            }

            // 4. Kiểm định tính hợp lệ của tệp thông qua Magic Bytes (không tin cậy Content-Type từ client).
            InspectedMedia inspected = inspectMagicBytes(tempFile, totalBytes, kind, durationHintMs);

            // 5. Sinh đường dẫn lưu trữ cô lập trên server: <yyyy>/<MM>/<uuid>.<safeExt>.
            ZonedDateTime now = clock.instant().atZone(clock.getZone());
            int year = now.get(ChronoField.YEAR);
            int month = now.get(ChronoField.MONTH_OF_YEAR);
            UUID attachmentUuid = UUID.randomUUID();
            String relativePath = String.format("%d/%02d/%s%s", year, month, attachmentUuid, inspected.safeExtension());

            Path targetPath = rootDir.resolve(relativePath).normalize();
            if (!targetPath.startsWith(rootDir)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid target path");
            }

            String sanitizedOriginalName = sanitizeFileName(file.getOriginalFilename());
            final Path stagedTemp = tempFile;
            final long finalSize = totalBytes;

            // 6. Mở transaction ngắn chỉ cho tranh quyền DB idempotent + công bố file nguyên tử.
            return transactionTemplate.execute(status -> publishAttachmentInTransaction(
                    actorId, clientId, kind, inspected, finalSize, relativePath,
                    sanitizedOriginalName, stagedTemp, targetPath));
        } catch (IOException e) {
            log.error("Lỗi I/O khi lưu tệp tải lên", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Failed to store media file");
        } finally {
            deleteQuietly(tempFile);
        }
    }

    private MediaUploadResult publishAttachmentInTransaction(
            UUID actorId,
            UUID clientId,
            String kind,
            InspectedMedia inspected,
            long totalBytes,
            String relativePath,
            String sanitizedOriginalName,
            Path stagedTemp,
            Path targetPath) {
        Path publishedPath = null;
        try {
            List<UUID> insertedIds = jdbc.query(
                    "INSERT INTO public.attachments ("
                            + "    uploader_id, upload_client_id, kind, mime, size_bytes, "
                            + "    storage_path, original_name, duration_ms, width, height"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (uploader_id, upload_client_id) WHERE upload_client_id IS NOT NULL DO NOTHING "
                            + "RETURNING id",
                    (rs, i) -> rs.getObject("id", UUID.class),
                    actorId, clientId, kind, inspected.mime(), totalBytes,
                    relativePath, sanitizedOriginalName, inspected.durationMs(), inspected.width(), inspected.height());

            if (insertedIds.isEmpty()) {
                // Luồng thua trong cuộc đua đồng thời cùng (actorId, clientId); finally bên ngoài sẽ xóa stagedTemp.
                MediaUploadResponse concurrentWinner = findExistingUpload(actorId, clientId, kind);
                if (concurrentWinner != null) {
                    return new MediaUploadResult(concurrentWinner, false);
                }
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Concurrent upload conflict");
            }

            // Chỉ luồng thắng cuộc công bố file từ .tmp sang vị trí lưu trữ chính thức và đăng ký dọn nếu rollback.
            Files.createDirectories(targetPath.getParent());
            try {
                Files.move(stagedTemp, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(stagedTemp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            publishedPath = targetPath;
            registerRollbackFileCleanup(targetPath);

            UUID newAttachmentId = insertedIds.get(0);
            MediaUploadResponse response = new MediaUploadResponse(
                    "/api/media/" + newAttachmentId,
                    inspected.mime(),
                    totalBytes,
                    inspected.width(),
                    inspected.height(),
                    inspected.durationMs(),
                    sanitizedOriginalName);
            return new MediaUploadResult(response, true);
        } catch (IOException e) {
            deleteQuietly(publishedPath);
            log.error("Lỗi I/O khi công bố tệp tải lên", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Failed to store media file");
        } catch (RuntimeException e) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                deleteQuietly(publishedPath);
            }
            throw e;
        }
    }

    /**
     * Chuẩn bị dữ liệu để stream tải tệp: kiểm tra quyền truy cập (RBAC theo mốc visible_from_message_id)
     * và tính hợp lệ của tệp trên ổ đĩa server.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MediaDownloadMeta prepareDownload(UUID actorId, UUID attachmentId) {
        if (actorId == null || attachmentId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid actorId or attachmentId");
        }

        List<AttachmentRecord> records = jdbc.query(
                "SELECT id, uploader_id, kind, mime, size_bytes, storage_path, original_name, revoked_at "
                        + "FROM public.attachments WHERE id = ?",
                (rs, i) -> new AttachmentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("uploader_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("mime"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_path"),
                        rs.getString("original_name"), null, null, null, rs.getTimestamp("revoked_at") != null),
                attachmentId);

        if (records.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found");
        }
        AttachmentRecord att = records.get(0);
        // Tệp bị gỡ không quay về quyền xem trước của người tải lên sau khi bỏ liên kết tin.
        if (att.revoked()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment is no longer available");
        }

        List<LinkedMessage> linkedMsgs = jdbc.query(
                "SELECT m.conversation_id, m.id AS message_id, m.recalled_at "
                        + "FROM public.messages m WHERE m.attachment_id = ?",
                (rs, i) -> new LinkedMessage(rs.getObject("conversation_id", UUID.class),
                        rs.getLong("message_id"), rs.getTimestamp("recalled_at") != null),
                attachmentId);

        if (!linkedMsgs.isEmpty()) {
            LinkedMessage lm = linkedMsgs.get(0);
            if (lm.recalled()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment is no longer available");
            }
            // Người yêu cầu phải là thành viên hiện tại VÀ message.id > visible_from_message_id (strict >)
            Boolean hasAccess = jdbc.queryForObject(
                    "SELECT EXISTS("
                            + "    SELECT 1 FROM public.conversation_members cm "
                            + "    WHERE cm.conversation_id = ? AND cm.user_id = ? "
                            + "      AND cm.left_at IS NULL AND ? > cm.visible_from_message_id "
                            + "      AND NOT EXISTS (SELECT 1 FROM public.message_deletions d "
                            + "                      WHERE d.user_id = cm.user_id AND d.message_id = ?)"
                            + ")",
                    Boolean.class,
                    lm.conversationId(), actorId, lm.messageId(), lm.messageId());

            if (!Boolean.TRUE.equals(hasAccess)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not authorized to access this media");
            }
        } else {
            // Chưa gắn vào message: chỉ chính người upload mới có quyền xem trước tệp của mình
            if (!att.uploaderId().equals(actorId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not authorized to access unattached media");
            }
        }

        Path filePath = rootDir.resolve(att.storagePath()).normalize();
        if (!filePath.startsWith(rootDir) || !Files.isRegularFile(filePath)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Media file not found on disk");
        }

        return new MediaDownloadMeta(
                att.id(),
                att.kind(),
                att.mime(),
                att.sizeBytes(),
                att.originalName(),
                filePath);
    }

    /** Đánh dấu thu hồi trong transaction gỡ tin, xóa tệp vật lý sau commit để rollback vẫn giữ được tệp. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void revokeAttachment(UUID attachmentId, Instant revokedAt) {
        List<String> paths = jdbc.queryForList("""
                UPDATE public.attachments SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL
                RETURNING storage_path
                """, String.class, java.sql.Timestamp.from(revokedAt), attachmentId);
        if (paths.isEmpty()) {
            return;
        }
        Path file = rootDir.resolve(paths.get(0)).normalize();
        if (!file.startsWith(rootDir) || file.equals(rootDir)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("Không thể xóa tệp đã thu hồi {}", attachmentId, e);
                }
            }
        });
    }

    private MediaUploadResponse findExistingUpload(UUID actorId, UUID clientId, String expectedKind) {
        List<AttachmentRecord> existing = jdbc.query(
                "SELECT id, uploader_id, kind, mime, size_bytes, storage_path, original_name, "
                        + "       duration_ms, width, height, revoked_at "
                        + "FROM public.attachments "
                        + "WHERE uploader_id = ? AND upload_client_id = ?",
                (rs, i) -> new AttachmentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("uploader_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("mime"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_path"),
                        rs.getString("original_name"),
                        (Integer) rs.getObject("duration_ms"),
                        (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"),
                        rs.getTimestamp("revoked_at") != null),
                actorId, clientId);

        if (existing.isEmpty()) {
            return null;
        }

        AttachmentRecord row = existing.get(0);
        if (row.revoked()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment is no longer available");
        }
        if (!expectedKind.equalsIgnoreCase(row.kind())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CONFLICT",
                    "upload_client_id already used with different kind: " + row.kind());
        }

        return new MediaUploadResponse(
                "/api/media/" + row.id(),
                row.mime(),
                row.sizeBytes(),
                row.width(),
                row.height(),
                row.durationMs(),
                row.originalName());
    }

    /** Đọc header magic bytes của file trên đĩa để phân loại, trích xuất metadata và loại trừ mã độc. */
    private InspectedMedia inspectMagicBytes(Path tempFile, long totalBytes, String kind, Integer durationHintMs)
            throws IOException {
        byte[] header = new byte[Math.min(4096, (int) totalBytes)];
        try (InputStream in = Files.newInputStream(tempFile)) {
            int read = readUpTo(in, header, header.length);
            if (read < header.length) {
                header = Arrays.copyOf(header, Math.max(0, read));
            }
        }

        if (isExecutableOrScript(header)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "Executable or script files are strictly prohibited");
        }

        return switch (kind) {
            case "IMAGE" -> inspectImage(tempFile, header);
            case "AUDIO" -> inspectAudio(tempFile, header, durationHintMs);
            case "VIDEO" -> inspectVideo(tempFile, header, durationHintMs);
            case "FILE" -> inspectFile(tempFile, header);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Unsupported media kind: " + kind);
        };
    }

    private InspectedMedia inspectImage(Path tempFile, byte[] header) {
        if (isHtmlOrSvg(header)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "SVG or HTML images are not allowed");
        }

        // 1. JPEG: FF D8 FF
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            Dimensions dims = readImageDimensions(tempFile);
            return new InspectedMedia("image/jpeg", ".jpg", dims.width(), dims.height(), null);
        }

        // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.length >= 8
                && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            Dimensions dims = readImageDimensions(tempFile);
            return new InspectedMedia("image/png", ".png", dims.width(), dims.height(), null);
        }

        // 3. WebP: RIFF....WEBP
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            Dimensions dims = parseWebPDimensions(header);
            return new InspectedMedia("image/webp", ".webp", dims.width(), dims.height(), null);
        }

        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                "Only JPEG, PNG and WebP images are supported");
    }

    private Dimensions readImageDimensions(Path tempFile) {
        try {
            BufferedImage image = ImageIO.read(tempFile.toFile());
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new Dimensions(image.getWidth(), image.getHeight());
            }
        } catch (Exception e) {
            log.warn("Không thể giải mã kích thước ảnh bằng ImageIO: {}", e.getMessage());
        }
        return new Dimensions(null, null);
    }

    private Dimensions parseWebPDimensions(byte[] header) {
        if (header.length < 30) {
            return new Dimensions(null, null);
        }
        // VP8 (lossy)
        if (header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == ' ') {
            if ((header[23] & 0xFF) == 0x9D && (header[24] & 0xFF) == 0x01 && (header[25] & 0xFF) == 0x2A) {
                int width = (header[26] & 0xFF) | ((header[27] & 0x3F) << 8);
                int height = (header[28] & 0xFF) | ((header[29] & 0x3F) << 8);
                // Ràng buộc DB yêu cầu width > 0 và height > 0; từ chối 415 thay vì để rơi vào lỗi DB 500.
                if (width <= 0 || height <= 0) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                            "Invalid WebP dimensions");
                }
                return new Dimensions(width, height);
            }
        }
        // VP8L (lossless): byte 20 bắt buộc là chữ ký 0x2F.
        if (header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == 'L'
                && header.length >= 25 && (header[20] & 0xFF) == 0x2F) {
            int b0 = header[21] & 0xFF;
            int b1 = header[22] & 0xFF;
            int b2 = header[23] & 0xFF;
            int b3 = header[24] & 0xFF;
            int width = 1 + (b0 | ((b1 & 0x3F) << 8));
            int height = 1 + (((b1 & 0xC0) >> 6) | (b2 << 2) | ((b3 & 0xF) << 10));
            return new Dimensions(width, height);
        }
        // VP8X (extended): bọc toàn bộ phép OR 24-bit trong ngoặc trước khi cộng 1.
        if (header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == 'X') {
            int width = 1 + ((header[24] & 0xFF) | ((header[25] & 0xFF) << 8) | ((header[26] & 0xFF) << 16));
            int height = 1 + ((header[27] & 0xFF) | ((header[28] & 0xFF) << 8) | ((header[29] & 0xFF) << 16));
            return new Dimensions(width, height);
        }
        return new Dimensions(null, null);
    }

    private InspectedMedia inspectAudio(Path tempFile, byte[] header, Integer durationHintMs) {
        if (durationHintMs != null && (durationHintMs < 0 || durationHintMs > 300_000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION",
                    "Audio duration must not exceed 300,000 ms (5 minutes)");
        }

        // ADTS AAC: FF F1 hoặc FF F9
        if (header.length >= 2 && (header[0] & 0xFF) == 0xFF && ((header[1] & 0xF6) == 0xF0)) {
            return new InspectedMedia("audio/aac", ".aac", null, null, durationHintMs);
        }

        // MP4 / M4A: kiểm tra box ftyp + thương hiệu major brand hợp lệ (chặn HEIC/AVIF/MOV giả danh audio).
        if (isAudioFtyp(header)) {
            Integer parsedDurationMs = parseIsoBmffDuration(tempFile);
            if (parsedDurationMs != null && (parsedDurationMs < 0 || parsedDurationMs > 300_000)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Audio duration exceeds 300,000 ms");
            }
            // Ưu tiên thời lượng thực tế trích từ box mvhd thay vì chỉ tin giá trị client gửi lên.
            Integer effectiveDuration = parsedDurationMs != null ? parsedDurationMs : durationHintMs;
            return new InspectedMedia("audio/mp4", ".m4a", null, null, effectiveDuration);
        }

        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                "Only AAC or MP4/M4A audio files are supported");
    }

    private InspectedMedia inspectVideo(Path tempFile, byte[] header, Integer durationHintMs) {
        if (durationHintMs != null && durationHintMs < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Video duration must be non-negative");
        }

        // MP4 / MOV: box ftyp
        if (isFtyp(header)) {
            Integer parsedDurationMs = parseIsoBmffDuration(tempFile);
            Integer effectiveDuration = parsedDurationMs != null ? parsedDurationMs : durationHintMs;
            String majorBrand = majorBrandOf(header);
            if ("qt  ".equals(majorBrand)) {
                return new InspectedMedia("video/quicktime", ".mov", null, null, effectiveDuration);
            }
            return new InspectedMedia("video/mp4", ".mp4", null, null, effectiveDuration);
        }

        // WebM: 1A 45 DF A3 (EBML ID)
        if (header.length >= 4
                && (header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3) {
            return new InspectedMedia("video/webm", ".webm", null, null, durationHintMs);
        }

        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                "Only MP4, QuickTime MOV, and WebM videos are supported");
    }

    private InspectedMedia inspectFile(Path tempFile, byte[] header) {
        // 0. Nghiêm cấm HTML và SVG dưới mọi hình thức (kể cả gửi qua kind=FILE) để tránh rủi ro XSS.
        if (isHtmlOrSvg(header)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "HTML and SVG files are strictly prohibited");
        }

        // 1. PDF: %PDF- (25 50 44 46 2D)
        if (header.length >= 5
                && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F' && header[4] == '-') {
            return new InspectedMedia("application/pdf", ".pdf", null, null, null);
        }

        // 2. ZIP / OOXML (docx, xlsx, pptx): 50 4B 03 04
        if (header.length >= 4
                && header[0] == 'P' && header[1] == 'K' && header[2] == 0x03 && header[3] == 0x04) {
            return inspectZipEntries(tempFile);
        }

        // 3. Plain text UTF-8 streaming toàn tệp (không chứa NUL, không chứa HTML/SVG sau khoảng trắng, không cắt giữa ký tự UTF-8).
        if (isValidUtf8PlainText(tempFile)) {
            return new InspectedMedia("text/plain", ".txt", null, null, null);
        }

        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                "Unsupported file type. Only PDF, Office/ZIP documents, and UTF-8 text are allowed");
    }

    /**
     * Đọc danh mục Central Directory của tệp ZIP qua ZipFile mà KHÔNG giải nén luồng dữ liệu
     * để miễn nhiễm Zip-Bomb DoS, chặn APK ở mọi cấp thư mục không phân biệt hoa thường.
     */
    private InspectedMedia inspectZipEntries(Path tempFile) {
        boolean hasContentTypes = false;
        boolean hasWord = false;
        boolean hasXl = false;
        boolean hasPpt = false;
        try (ZipFile zipFile = new ZipFile(tempFile.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                            "ZIP archive contains too many entries");
                }
                ZipEntry entry = entries.nextElement();
                String rawName = entry.getName();
                String normalizedLower = rawName.replace('\\', '/').toLowerCase(Locale.ROOT);
                // Nghiêm cấm tuyệt đối gói APK chứa AndroidManifest.xml (bất kể chữ hoa/thường hay thư mục con).
                if ("androidmanifest.xml".equals(normalizedLower)
                        || normalizedLower.endsWith("/androidmanifest.xml")) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                            "APK packages are strictly prohibited");
                }
                if ("[Content_Types].xml".equals(rawName)) {
                    hasContentTypes = true;
                } else if (rawName.startsWith("word/")) {
                    hasWord = true;
                } else if (rawName.startsWith("xl/")) {
                    hasXl = true;
                } else if (rawName.startsWith("ppt/")) {
                    hasPpt = true;
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "Invalid ZIP or Office file");
        }
        if (hasContentTypes && hasWord) {
            return new InspectedMedia(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx", null, null, null);
        }
        if (hasContentTypes && hasXl) {
            return new InspectedMedia(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx", null, null, null);
        }
        if (hasContentTypes && hasPpt) {
            return new InspectedMedia(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx", null, null, null);
        }
        return new InspectedMedia("application/zip", ".zip", null, null, null);
    }

    /**
     * Kiểm tra tệp văn bản UTF-8 toàn tệp theo cơ chế streaming với CharsetDecoder giữ trạng thái giữa các khối 8KB
     * (không báo lỗi giả khi ký tự tiếng Việt 3 byte rơi đúng ranh giới 64KB, đồng thời phát hiện NUL ở cuối tệp và HTML/SVG có khoảng trắng đệm).
     */
    private boolean isValidUtf8PlainText(Path tempFile) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] raw = new byte[8192];
        ByteBuffer inBuf = ByteBuffer.allocate(8192 + 8);
        CharBuffer outBuf = CharBuffer.allocate(8192);
        StringBuilder leadingChars = new StringBuilder();
        boolean sawAnyByte = false;

        try (InputStream in = Files.newInputStream(tempFile)) {
            int read;
            while ((read = in.read(raw)) != -1) {
                sawAnyByte = true;
                for (int i = 0; i < read; i++) {
                    if (raw[i] == 0x00) {
                        return false; // Chứa NUL byte -> tệp nhị phân
                    }
                }
                inBuf.put(raw, 0, read);
                inBuf.flip();
                while (true) {
                    outBuf.clear();
                    CoderResult res = decoder.decode(inBuf, outBuf, false);
                    outBuf.flip();
                    appendNonLeadingWhitespace(leadingChars, outBuf);
                    if (res.isError()) {
                        return false;
                    }
                    if (res.isUnderflow()) {
                        break;
                    }
                }
                inBuf.compact();
            }
            if (!sawAnyByte) {
                return false;
            }
            inBuf.flip();
            while (true) {
                outBuf.clear();
                CoderResult res = decoder.decode(inBuf, outBuf, true);
                outBuf.flip();
                appendNonLeadingWhitespace(leadingChars, outBuf);
                if (res.isError()) {
                    return false;
                }
                if (res.isUnderflow()) {
                    break;
                }
            }
            outBuf.clear();
            if (decoder.flush(outBuf).isError()) {
                return false;
            }
            outBuf.flip();
            appendNonLeadingWhitespace(leadingChars, outBuf);

            // Chặn HTML/SVG dù bị đệm nhiều khoảng trắng hoặc BOM trước thẻ mở.
            return !containsHtmlOrSvgTag(leadingChars.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private static void appendNonLeadingWhitespace(StringBuilder dst, CharBuffer src) {
        while (src.hasRemaining() && dst.length() < MAX_HTML_SCAN_CHARS) {
            char c = src.get();
            if (dst.length() == 0 && (c == '\uFEFF' || Character.isWhitespace(c))) {
                continue;
            }
            dst.append(c);
        }
    }

    private boolean isFtyp(byte[] header) {
        return header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    private boolean isAudioFtyp(byte[] header) {
        if (!isFtyp(header) || header.length < 12) {
            return false;
        }
        String brand = majorBrandOf(header);
        return brand != null && ALLOWED_AUDIO_FTYP_BRANDS.contains(brand);
    }

    private static String majorBrandOf(byte[] header) {
        if (header.length < 12) {
            return null;
        }
        return new String(header, 8, 4, StandardCharsets.ISO_8859_1);
    }

    private boolean isExecutableOrScript(byte[] header) {
        if (header.length >= 2 && header[0] == 'M' && header[1] == 'Z') {
            return true; // Windows PE Executable
        }
        if (header.length >= 4 && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
            return true; // Linux ELF Executable
        }
        if (header.length >= 2 && header[0] == '#' && header[1] == '!') {
            return true; // Unix shebang script
        }
        return false;
    }

    private boolean isHtmlOrSvg(byte[] header) {
        int offset = 0;
        if (header.length >= 3
                && (header[0] & 0xFF) == 0xEF
                && (header[1] & 0xFF) == 0xBB
                && (header[2] & 0xFF) == 0xBF) {
            offset = 3; // Bỏ qua UTF-8 BOM nếu có
        }
        int len = Math.min(header.length - offset, 1024);
        if (len <= 0) {
            return false;
        }
        String prefix = new String(header, offset, len, StandardCharsets.US_ASCII);
        return containsHtmlOrSvgTag(prefix);
    }

    private static boolean containsHtmlOrSvgTag(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<html")
                || lower.contains("<!doctype html")
                || lower.contains("<svg")
                || lower.contains("<?xml")
                || lower.contains("<script");
    }

    /**
     * Phân tích box mvhd trong ISOBMFF (MP4/M4A/MOV) để lấy thời lượng mili-giây an toàn:
     * xử lý đúng box 64-bit (header 16 byte), đọc/bỏ qua đủ byte và tính toán bằng BigInteger chống tràn int/long.
     */
    private Integer parseIsoBmffDuration(Path tempFile) {
        try (FileInputStream fis = new FileInputStream(tempFile.toFile())) {
            long remaining = Files.size(tempFile);
            byte[] headerBuf = new byte[8];
            while (remaining >= 8) {
                if (!readFully(fis, headerBuf, 8)) {
                    break;
                }
                long boxSize = readUnsignedInt32(headerBuf, 0);
                String boxType = new String(headerBuf, 4, 4, StandardCharsets.ISO_8859_1);
                long headerSize = 8L;

                if (boxSize == 1L) {
                    byte[] extBuf = new byte[8];
                    if (remaining < 16 || !readFully(fis, extBuf, 8)) {
                        break;
                    }
                    boxSize = ByteBuffer.wrap(extBuf).getLong();
                    headerSize = 16L;
                    if (boxSize < 16L || boxSize > remaining) {
                        break;
                    }
                } else if (boxSize == 0L) {
                    boxSize = remaining;
                } else if (boxSize < 8L || boxSize > remaining) {
                    break;
                }

                long payloadSize = boxSize - headerSize;
                if ("moov".equals(boxType)) {
                    return parseMvhdInsideMoov(fis, payloadSize);
                }
                if (payloadSize > 0 && !skipFully(fis, payloadSize)) {
                    break;
                }
                remaining -= boxSize;
            }
        } catch (Exception e) {
            log.debug("Không thể đọc box mvhd từ tệp ISOBMFF: {}", e.getMessage());
        }
        return null;
    }

    private Integer parseMvhdInsideMoov(FileInputStream fis, long moovBytes) throws IOException {
        long remaining = moovBytes;
        byte[] headerBuf = new byte[8];
        while (remaining >= 8) {
            if (!readFully(fis, headerBuf, 8)) {
                break;
            }
            long boxSize = readUnsignedInt32(headerBuf, 0);
            String boxType = new String(headerBuf, 4, 4, StandardCharsets.ISO_8859_1);
            long headerSize = 8L;

            if (boxSize == 1L) {
                byte[] extBuf = new byte[8];
                if (remaining < 16 || !readFully(fis, extBuf, 8)) {
                    break;
                }
                boxSize = ByteBuffer.wrap(extBuf).getLong();
                headerSize = 16L;
                if (boxSize < 16L || boxSize > remaining) {
                    break;
                }
            } else if (boxSize == 0L) {
                boxSize = remaining;
            } else if (boxSize < 8L || boxSize > remaining) {
                break;
            }

            long payloadSize = boxSize - headerSize;
            if ("mvhd".equals(boxType)) {
                int toRead = (int) Math.min(payloadSize, 32L);
                if (toRead < 20) {
                    return null;
                }
                byte[] mvhdData = new byte[toRead];
                if (!readFully(fis, mvhdData, toRead)) {
                    return null;
                }
                int version = mvhdData[0] & 0xFF;
                if (version == 0 && mvhdData.length >= 20) {
                    long timescale = readUnsignedInt32(mvhdData, 12);
                    long duration = readUnsignedInt32(mvhdData, 16);
                    return computeDurationMsSafe(BigInteger.valueOf(duration), timescale);
                } else if (version == 1 && mvhdData.length >= 32) {
                    long timescale = readUnsignedInt32(mvhdData, 20);
                    byte[] durBytes = Arrays.copyOfRange(mvhdData, 24, 32);
                    return computeDurationMsSafe(new BigInteger(1, durBytes), timescale);
                }
                return null;
            }
            if (payloadSize > 0 && !skipFully(fis, payloadSize)) {
                break;
            }
            remaining -= boxSize;
        }
        return null;
    }

    /** Tính (duration * 1000) / timescale bằng BigInteger và kẹp về Integer.MAX_VALUE để tuyệt đối không tràn số. */
    private static Integer computeDurationMsSafe(BigInteger duration, long timescale) {
        if (timescale <= 0L || duration.signum() < 0) {
            return null;
        }
        BigInteger ms = duration.multiply(THOUSAND).divide(BigInteger.valueOf(timescale));
        if (ms.compareTo(MAX_INT_BIG) > 0) {
            return Integer.MAX_VALUE;
        }
        return ms.intValue();
    }

    private static long readUnsignedInt32(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF) << 24)
                | ((long) (buf[offset + 1] & 0xFF) << 16)
                | ((long) (buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    private static int readUpTo(InputStream in, byte[] dst, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(dst, total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static boolean readFully(InputStream in, byte[] dst, int len) throws IOException {
        return readUpTo(in, dst, len) == len;
    }

    private static boolean skipFully(InputStream in, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        byte[] discard = null;
        while (remaining > 0L) {
            long skipped = in.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (discard == null) {
                discard = new byte[4096];
            }
            int read = in.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                return false;
            }
            remaining -= read;
        }
        return true;
    }

    private String sanitizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "attachment";
        }
        // Làm sạch thủ công thay vì Paths.get() trên chuỗi client chưa tin cậy (tránh InvalidPathException với NUL/ký tự điều khiển).
        String normalized = originalName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        StringBuilder cleaned = new StringBuilder(baseName.length());
        for (int i = 0; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            if (Character.isISOControl(c) || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString().trim();
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        if (result.isEmpty()) {
            return "attachment";
        }
        if (result.length() > 255) {
            result = result.substring(0, 255);
        }
        return result;
    }

    /** Đăng ký xóa tệp đã công bố trên đĩa nếu DB transaction bị rollback. */
    private void registerRollbackFileCleanup(Path publishedPath) {
        if (publishedPath == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(publishedPath);
                }
            }
        });
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record Dimensions(Integer width, Integer height) {}

    private record InspectedMedia(
            String mime,
            String safeExtension,
            Integer width,
            Integer height,
            Integer durationMs) {}

    private record AttachmentRecord(
            UUID id,
            UUID uploaderId,
            String kind,
            String mime,
            long sizeBytes,
            String storagePath,
            String originalName,
            Integer durationMs,
            Integer width,
            Integer height,
            boolean revoked) {}

    private record LinkedMessage(UUID conversationId, long messageId, boolean recalled) {}
}
