package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Con trỏ sự kiện chỉ tăng sau khi transaction đồng bộ đã thành công. */
@Entity(tableName = "sync_state")
public class SyncStateEntity {
    /** Tách con trỏ theo tài khoản để không trộn dữ liệu. */
    @PrimaryKey @NonNull
    private String userId;

    /** Cursor sync_events; không dùng messageId thay thế. */
    private long lastSyncEventId;

    /** Lần đồng bộ gần nhất, UTC mili-giây. */
    private long lastSyncAt;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public SyncStateEntity() { }

    // Đọc và cập nhật userId khi ánh xạ DTO hoặc khôi phục cache.
    public String getUserId() { return userId; }
    public void setUserId(String value) { this.userId = value; }

    // Đọc và cập nhật lastSyncEventId khi ánh xạ DTO hoặc khôi phục cache.
    public long getLastSyncEventId() { return lastSyncEventId; }
    public void setLastSyncEventId(long value) { this.lastSyncEventId = value; }

    // Đọc và cập nhật lastSyncAt khi ánh xạ DTO hoặc khôi phục cache.
    public long getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(long value) { this.lastSyncAt = value; }
}
