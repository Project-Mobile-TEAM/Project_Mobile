package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Thành viên và mốc receipt theo người dùng, không phải từng thiết bị. */
@Entity(tableName = "member", primaryKeys = {"conversationId", "userId"})
public class ConversationMemberEntity {
    /** Hội thoại chứa thành viên. */
    @NonNull
    private String conversationId;

    /** UUID thành viên. */
    @NonNull
    private String userId;

    /** ADMIN hoặc MEMBER. */
    @NonNull
    private String role;

    /** Ít nhất một thiết bị đã lưu tới mốc này. */
    private long lastDeliveredId;

    /** Ít nhất một thiết bị đã đọc tới mốc này. */
    private long lastReadId;

    /** Thành viên đã rời nhóm hay chưa. */
    private boolean left;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public ConversationMemberEntity() { }

    // Đọc và cập nhật conversationId khi ánh xạ DTO hoặc khôi phục cache.
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { this.conversationId = value; }

    // Đọc và cập nhật userId khi ánh xạ DTO hoặc khôi phục cache.
    public String getUserId() { return userId; }
    public void setUserId(String value) { this.userId = value; }

    // Đọc và cập nhật role khi ánh xạ DTO hoặc khôi phục cache.
    public String getRole() { return role; }
    public void setRole(String value) { this.role = value; }

    // Đọc và cập nhật lastDeliveredId khi ánh xạ DTO hoặc khôi phục cache.
    public long getLastDeliveredId() { return lastDeliveredId; }
    public void setLastDeliveredId(long value) { this.lastDeliveredId = value; }

    // Đọc và cập nhật lastReadId khi ánh xạ DTO hoặc khôi phục cache.
    public long getLastReadId() { return lastReadId; }
    public void setLastReadId(long value) { this.lastReadId = value; }

    // Đọc và cập nhật left khi ánh xạ DTO hoặc khôi phục cache.
    public boolean getLeft() { return left; }
    public void setLeft(boolean value) { this.left = value; }
}
