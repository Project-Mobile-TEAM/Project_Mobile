package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Hội thoại cache với thông tin tóm tắt phục vụ danh sách offline. */
@Entity(tableName = "conversation")
public class ConversationEntity {
    /** UUID hội thoại. */
    @PrimaryKey @NonNull
    private String id;

    /** DIRECT hoặc GROUP. */
    @NonNull
    private String type;

    /** Tên nhóm hoặc tên hội thoại hiển thị. */
    @Nullable
    private String title;

    /** Ảnh nhóm nếu có. */
    @Nullable
    private String avatarUrl;

    /** ID tin cuối từ server, null khi chưa có tin. */
    @Nullable
    private Long lastMessageId;

    /** Thời gian tin cuối để sắp xếp danh sách. */
    private long lastMessageAt;

    /** Số tin chưa đọc được tính khi đồng bộ. */
    private int unreadCount;

    /** Tùy chọn tắt thông báo trên máy. */
    private boolean muted;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public ConversationEntity() { }

    // Đọc và cập nhật id khi ánh xạ DTO hoặc khôi phục cache.
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }

    // Đọc và cập nhật type khi ánh xạ DTO hoặc khôi phục cache.
    public String getType() { return type; }
    public void setType(String value) { this.type = value; }

    // Đọc và cập nhật title khi ánh xạ DTO hoặc khôi phục cache.
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }

    // Đọc và cập nhật avatarUrl khi ánh xạ DTO hoặc khôi phục cache.
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { this.avatarUrl = value; }

    // Đọc và cập nhật lastMessageId khi ánh xạ DTO hoặc khôi phục cache.
    public Long getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(Long value) { this.lastMessageId = value; }

    // Đọc và cập nhật lastMessageAt khi ánh xạ DTO hoặc khôi phục cache.
    public long getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(long value) { this.lastMessageAt = value; }

    // Đọc và cập nhật unreadCount khi ánh xạ DTO hoặc khôi phục cache.
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int value) { this.unreadCount = value; }

    // Đọc và cập nhật muted khi ánh xạ DTO hoặc khôi phục cache.
    public boolean getMuted() { return muted; }
    public void setMuted(boolean value) { this.muted = value; }
}
