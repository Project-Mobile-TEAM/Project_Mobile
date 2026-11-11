package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Hồ sơ công khai đã gặp; tuyệt đối không chứa mật khẩu hoặc OTP. */
@Entity(tableName = "user_cache", indices = {@Index(value = "phone", unique = true)})
public class UserEntity {
    /** UUID người dùng từ server. */
    @PrimaryKey @NonNull
    private String id;

    /** Số E.164; có thể null sau khi xóa tài khoản. */
    @Nullable
    private String phone;

    /** Tên hiển thị trong danh bạ và tin nhắn. */
    @NonNull
    private String displayName;

    /** Ảnh đại diện công khai, có thể chưa đặt. */
    @Nullable
    private String avatarUrl;

    /** Quyền nhận tin từ người lạ của hồ sơ cache. */
    private boolean allowMessagesFromNonContacts;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public UserEntity() { }

    // Đọc và cập nhật id khi ánh xạ DTO hoặc khôi phục cache.
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }

    // Đọc và cập nhật phone khi ánh xạ DTO hoặc khôi phục cache.
    public String getPhone() { return phone; }
    public void setPhone(String value) { this.phone = value; }

    // Đọc và cập nhật displayName khi ánh xạ DTO hoặc khôi phục cache.
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { this.displayName = value; }

    // Đọc và cập nhật avatarUrl khi ánh xạ DTO hoặc khôi phục cache.
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { this.avatarUrl = value; }

    // Đọc và cập nhật allowMessagesFromNonContacts khi ánh xạ DTO hoặc khôi phục cache.
    public boolean getAllowMessagesFromNonContacts() { return allowMessagesFromNonContacts; }
    public void setAllowMessagesFromNonContacts(boolean value) { this.allowMessagesFromNonContacts = value; }
}
