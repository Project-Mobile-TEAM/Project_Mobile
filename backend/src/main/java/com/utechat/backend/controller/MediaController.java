package com.utechat.backend.controller;

import com.utechat.backend.dto.MediaDtos.MediaDownloadMeta;
import com.utechat.backend.dto.MediaDtos.MediaUploadResponse;
import com.utechat.backend.dto.MediaDtos.MediaUploadResult;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.MediaService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * REST Controller xử lý upload và streaming đa phương tiện (/api/media/**).
 * Bảo mật: Xác thực bắt buộc qua Authorization: Bearer token (không nhận query ticket/token trong URL),
 * hỗ trợ HTTP Range 206/416 cho video/audio streaming, và luôn đính kèm header X-Content-Type-Options: nosniff.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Tải tệp lên server qua multipart/form-data.
     * Trả về 201 Created cho upload mới, hoặc 200 OK cho replay upload theo clientId.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaUploadResponse> upload(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam("clientId") String rawClientId,
            @RequestParam("kind") String rawKind,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "durationMs", required = false) Integer durationMs) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Authentication required");
        }
        UUID clientId = parseRequiredUuid(rawClientId, "Invalid clientId");
        MediaUploadResult result = mediaService.storeUpload(actor.userId(), clientId, rawKind, file, durationMs);

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    /**
     * Tải dữ liệu tệp hoặc stream từng đoạn byte (HTTP Range).
     * Chỉ chấp nhận Bearer token trong header. Kiểm tra quyền thành viên hội thoại theo mốc visible_from_message_id.
     */
    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable("id") String rawId,
            @RequestHeader HttpHeaders headers) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Authentication required");
        }
        UUID attachmentId = parsePathUuid(rawId);
        MediaDownloadMeta meta = mediaService.prepareDownload(actor.userId(), attachmentId);
        long totalLength = meta.sizeBytes();

        // 1. Phân tích header Range (nếu có)
        List<HttpRange> ranges = null;
        try {
            ranges = headers.getRange();
        } catch (IllegalArgumentException badRange) {
            // Header Range sai định dạng -> 416 Range Not Satisfiable
            return rangeNotSatisfiable(totalLength);
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(meta.mime());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        String safeFileName = (meta.fileName() == null || meta.fileName().isBlank())
                ? "attachment" : meta.fileName();
        ContentDisposition disposition = "FILE".equalsIgnoreCase(meta.kind())
                ? ContentDisposition.attachment().filename(safeFileName, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(safeFileName, StandardCharsets.UTF_8).build();

        // 2. Không có Range -> Trả toàn bộ nội dung (200 OK)
        if (ranges == null || ranges.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalLength))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(mediaType)
                    .body(outputStream -> {
                        try (FileChannel channel = FileChannel.open(meta.filePath(), StandardOpenOption.READ)) {
                            ByteBuffer buffer = ByteBuffer.allocate(8192);
                            while (channel.read(buffer) > 0) {
                                buffer.flip();
                                outputStream.write(buffer.array(), buffer.position(), buffer.remaining());
                                buffer.clear();
                            }
                            outputStream.flush();
                        }
                    });
        }

        // 3. Có Range: Từ chối multi-range (ranges.size() > 1) hoặc file rỗng
        if (ranges.size() > 1 || totalLength <= 0) {
            return rangeNotSatisfiable(totalLength);
        }

        HttpRange range = ranges.get(0);
        long start;
        long end;
        try {
            start = range.getRangeStart(totalLength);
            end = range.getRangeEnd(totalLength);
        } catch (IllegalArgumentException e) {
            return rangeNotSatisfiable(totalLength);
        }

        // Kiểm tra phạm vi hợp lệ
        if (start < 0 || start >= totalLength || start > end || end >= totalLength) {
            return rangeNotSatisfiable(totalLength);
        }

        // 4. Trả về đúng đoạn byte được yêu cầu (206 Partial Content)
        long rangeLength = end - start + 1;
        String contentRange = "bytes " + start + "-" + end + "/" + totalLength;

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, contentRange)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(rangeLength))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType)
                .body(outputStream -> {
                    try (FileChannel channel = FileChannel.open(meta.filePath(), StandardOpenOption.READ)) {
                        channel.position(start);
                        long remaining = rangeLength;
                        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(8192L, remaining));
                        while (remaining > 0) {
                            buffer.limit((int) Math.min((long) buffer.capacity(), remaining));
                            int read = channel.read(buffer);
                            if (read <= 0) {
                                break;
                            }
                            buffer.flip();
                            outputStream.write(buffer.array(), buffer.position(), buffer.remaining());
                            remaining -= read;
                            buffer.clear();
                        }
                        outputStream.flush();
                    }
                });
    }

    private ResponseEntity<StreamingResponseBody> rangeNotSatisfiable(long totalLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalLength)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    private static UUID parseRequiredUuid(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
    }

    private static UUID parsePathUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
        }
    }
}
