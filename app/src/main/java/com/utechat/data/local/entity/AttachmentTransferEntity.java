package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Tiến độ upload/download và metadata tệp cục bộ giữ nguyên qua các lần khởi động lại app (Room v3, D-03/D-04). */
@Entity(tableName = "attachment_transfer")
public class AttachmentTransferEntity {
    /** Liên kết công việc WorkManager với tin nhắn hoặc mã tải xuống. */
    @PrimaryKey @NonNull
    private String clientId;

    /** URI ContentResolver/FileProvider, không phải đường dẫn tùy ý từ ngoài app. */
    @NonNull
    private String localUri;

    /** Trạng thái chờ, đang tải, đã tải lên hoặc lỗi (PENDING/UPLOADING/UPLOADED/DOWNLOADING/COMPLETED/FAILED). */
    @NonNull
    private String state;

    /** Phần trăm tiến độ 0 đến 100. */
    private int progress;

    /** Đường dẫn tương đối /api/media/{id} do server cấp sau upload hoặc dùng để download. */
    @Nullable
    private String mediaUrl;

    /** Chiều truyền tệp: UPLOAD hoặc DOWNLOAD (mặc định UPLOAD khi nâng cấp từ v2). */
    @NonNull
    @ColumnInfo(defaultValue = "'UPLOAD'")
    private String direction = "UPLOAD";

    /** Đường dẫn tệp riêng tư bên trong thư mục pending_media/ hoặc media_cache/ của app để sống sót qua restart. */
    @Nullable
    private String localPath;

    /** Loại media chuẩn backend: IMAGE, AUDIO, VIDEO, FILE. */
    @Nullable
    private String kind;

    /** Kiểu MIME thực tế do app xác định hoặc backend xác nhận. */
    @Nullable
    private String mime;

    /** Kích thước tệp tính theo byte. */
    @Nullable
    private Long sizeBytes;

    /** Tên tệp gốc hiển thị trên giao diện. */
    @Nullable
    private String fileName;

    /** Thời lượng tính theo mili giây (AUDIO/VIDEO). */
    @Nullable
    private Integer durationMs;

    /** Chiều rộng ảnh/video theo pixel. */
    @Nullable
    private Integer width;

    /** Chiều cao ảnh/video theo pixel. */
    @Nullable
    private Integer height;

    /** Mã lỗi bền vững khi truyền thất bại (ví dụ FILE_TOO_LARGE, UNSUPPORTED_TYPE, NETWORK). */
    @Nullable
    private String errorCode;

    /** Mốc thời gian cập nhật gần nhất theo epoch milliseconds. */
    @ColumnInfo(defaultValue = "0")
    private long updatedAt;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public AttachmentTransferEntity() { }

    // Đọc và cập nhật clientId khi ánh xạ DTO hoặc khôi phục cache.
    @NonNull public String getClientId() { return clientId; }
    public void setClientId(@NonNull String value) { this.clientId = value; }

    // Đọc và cập nhật localUri khi ánh xạ DTO hoặc khôi phục cache.
    @NonNull public String getLocalUri() { return localUri; }
    public void setLocalUri(@NonNull String value) { this.localUri = value; }

    // Đọc và cập nhật state khi ánh xạ DTO hoặc khôi phục cache.
    @NonNull public String getState() { return state; }
    public void setState(@NonNull String value) { this.state = value; }

    // Đọc và cập nhật progress (0..100).
    public int getProgress() { return progress; }
    public void setProgress(int value) { this.progress = value; }

    // Đọc và cập nhật mediaUrl (/api/media/{id}).
    @Nullable public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(@Nullable String value) { this.mediaUrl = value; }

    // Đọc và cập nhật chiều truyền tệp (UPLOAD / DOWNLOAD).
    @NonNull public String getDirection() { return direction; }
    public void setDirection(@NonNull String value) { this.direction = value; }

    // Đọc và cập nhật đường dẫn tệp trong bộ nhớ riêng tư của ứng dụng.
    @Nullable public String getLocalPath() { return localPath; }
    public void setLocalPath(@Nullable String value) { this.localPath = value; }

    // Đọc và cập nhật loại media (IMAGE / AUDIO / VIDEO / FILE).
    @Nullable public String getKind() { return kind; }
    public void setKind(@Nullable String value) { this.kind = value; }

    // Đọc và cập nhật kiểu MIME.
    @Nullable public String getMime() { return mime; }
    public void setMime(@Nullable String value) { this.mime = value; }

    // Đọc và cập nhật kích thước tệp (byte).
    @Nullable public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(@Nullable Long value) { this.sizeBytes = value; }

    // Đọc và cập nhật tên tệp gốc hiển thị cho người dùng.
    @Nullable public String getFileName() { return fileName; }
    public void setFileName(@Nullable String value) { this.fileName = value; }

    // Đọc và cập nhật thời lượng (ms) cho AUDIO/VIDEO.
    @Nullable public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(@Nullable Integer value) { this.durationMs = value; }

    // Đọc và cập nhật chiều rộng ảnh/video (px).
    @Nullable public Integer getWidth() { return width; }
    public void setWidth(@Nullable Integer value) { this.width = value; }

    // Đọc và cập nhật chiều cao ảnh/video (px).
    @Nullable public Integer getHeight() { return height; }
    public void setHeight(@Nullable Integer value) { this.height = value; }

    // Đọc và cập nhật mã lỗi bền vững khi upload/download thất bại.
    @Nullable public String getErrorCode() { return errorCode; }
    public void setErrorCode(@Nullable String value) { this.errorCode = value; }

    // Đọc và cập nhật mốc thời gian thay đổi trạng thái gần nhất (epoch ms).
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { this.updatedAt = value; }
}
