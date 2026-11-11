package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Tiến độ upload/download giữ được qua lần khởi động app. */
@Entity(tableName = "attachment_transfer")
public class AttachmentTransferEntity {
    /** Liên kết công việc WorkManager với tin nhắn. */
    @PrimaryKey @NonNull
    private String clientId;

    /** URI ContentResolver/FileProvider, không phải đường dẫn tùy ý. */
    @NonNull
    private String localUri;

    /** Trạng thái chờ, đang tải, hoàn tất hoặc lỗi. */
    @NonNull
    private String state;

    /** Phần trăm tiến độ 0 đến 100. */
    private int progress;

    /** URL API do server cấp sau upload. */
    @Nullable
    private String mediaUrl;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public AttachmentTransferEntity() { }

    // Đọc và cập nhật clientId khi ánh xạ DTO hoặc khôi phục cache.
    public String getClientId() { return clientId; }
    public void setClientId(String value) { this.clientId = value; }

    // Đọc và cập nhật localUri khi ánh xạ DTO hoặc khôi phục cache.
    public String getLocalUri() { return localUri; }
    public void setLocalUri(String value) { this.localUri = value; }

    // Đọc và cập nhật state khi ánh xạ DTO hoặc khôi phục cache.
    public String getState() { return state; }
    public void setState(String value) { this.state = value; }

    // Đọc và cập nhật progress khi ánh xạ DTO hoặc khôi phục cache.
    public int getProgress() { return progress; }
    public void setProgress(int value) { this.progress = value; }

    // Đọc và cập nhật mediaUrl khi ánh xạ DTO hoặc khôi phục cache.
    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String value) { this.mediaUrl = value; }
}
