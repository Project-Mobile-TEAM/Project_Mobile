package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Danh bạ của tài khoản đang đăng nhập; xóa cache khi đổi tài khoản. */
@Entity(tableName = "contact", indices = {@Index("blocked")})
public class ContactEntity {
    /** UUID bạn bè hoặc người bị chặn. */
    @PrimaryKey @NonNull
    private String contactUserId;

    /** true là bị chặn; false là bạn bè. */
    private boolean blocked;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public ContactEntity() { }

    // Đọc và cập nhật contactUserId khi ánh xạ DTO hoặc khôi phục cache.
    public String getContactUserId() { return contactUserId; }
    public void setContactUserId(String value) { this.contactUserId = value; }

    // Đọc và cập nhật blocked khi ánh xạ DTO hoặc khôi phục cache.
    public boolean getBlocked() { return blocked; }
    public void setBlocked(boolean value) { this.blocked = value; }
}
