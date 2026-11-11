package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Lời mời gửi đến/đi được đồng bộ từ server. */
@Entity(tableName = "contact_request")
public class ContactRequestEntity {
    /** UUID lời mời. */
    @PrimaryKey @NonNull
    private String id;

    /** Người gửi lời mời. */
    @NonNull
    private String requesterId;

    /** Người nhận lời mời. */
    @NonNull
    private String addresseeId;

    /** PENDING, ACCEPTED, REJECTED hoặc CANCELED. */
    @NonNull
    private String status;

    /** Thời điểm tạo, tính bằng mili-giây UTC. */
    private long createdAt;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public ContactRequestEntity() { }

    // Đọc và cập nhật id khi ánh xạ DTO hoặc khôi phục cache.
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }

    // Đọc và cập nhật requesterId khi ánh xạ DTO hoặc khôi phục cache.
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String value) { this.requesterId = value; }

    // Đọc và cập nhật addresseeId khi ánh xạ DTO hoặc khôi phục cache.
    public String getAddresseeId() { return addresseeId; }
    public void setAddresseeId(String value) { this.addresseeId = value; }

    // Đọc và cập nhật status khi ánh xạ DTO hoặc khôi phục cache.
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }

    // Đọc và cập nhật createdAt khi ánh xạ DTO hoặc khôi phục cache.
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { this.createdAt = value; }
}
