package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Tập hợp các DTO bất biến (Java 17 record) phục vụ upload và tải file đa phương tiện (Phase 6).
 */
public final class MediaDtos {
    private MediaDtos() {
    }

    /**
     * Phản hồi sau khi upload file thành công hoặc khi replay upload theo clientId (API_CONTRACT mục 2.5).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaUploadResponse(
            String mediaUrl,
            String mime,
            long sizeBytes,
            Integer width,
            Integer height,
            Integer durationMs,
            String fileName) {
    }

    /**
     * Kết quả upload kèm cờ created để Controller trả HTTP 201 (mới tạo) hoặc 200 (replay trùng clientId).
     */
    public record MediaUploadResult(
            MediaUploadResponse response,
            boolean created) {
    }

    /**
     * Metadata dùng nội bộ giữa MediaService và MediaController để stream dữ liệu file từ ổ đĩa.
     */
    public record MediaDownloadMeta(
            UUID attachmentId,
            String kind,
            String mime,
            long sizeBytes,
            String fileName,
            Path filePath) {
    }
}
