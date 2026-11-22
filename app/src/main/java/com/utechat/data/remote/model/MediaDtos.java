package com.utechat.data.remote.model;

import androidx.annotation.Nullable;

/** DTO Gson cho API tải tệp đa phương tiện (POST /api/media); tách biệt hoàn toàn khỏi Room Entity. */
public final class MediaDtos {
    private MediaDtos() { }

    /**
     * Kết quả upload do server trả về (201 mới tạo hoặc 200 khi upload lại trùng clientId):
     * mediaUrl luôn là đường dẫn tương đối /api/media/{id}, không bao giờ chứa token hay IP nội bộ.
     */
    public static final class MediaUploadResponse {
        @Nullable public String mediaUrl;
        @Nullable public String mime;
        @Nullable public Long sizeBytes;
        @Nullable public Integer width;
        @Nullable public Integer height;
        @Nullable public Integer durationMs;
        @Nullable public String fileName;
    }
}
