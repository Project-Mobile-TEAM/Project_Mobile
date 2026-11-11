package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.*;

/** Tin nhắn offline: localId ổn định, serverId chỉ có sau khi gửi thành công. */
@Entity(tableName = "message", indices = {@Index(value = "clientId", unique = true), @Index(value = "serverId", unique = true), @Index(value = {"conversationId", "serverId"})})
public class MessageEntity {
    /** Khóa cục bộ; không dùng ID này làm cursor server. */
    @PrimaryKey(autoGenerate = true)
    private long localId;

    /** ID BIGINT từ server; null khi tin còn nằm trong hàng đợi. */
    @Nullable
    private Long serverId;

    /** UUID chống gửi trùng và giữ nguyên qua retry. */
    @NonNull
    private String clientId;

    /** UUID hội thoại nhận tin. */
    @NonNull
    private String conversationId;

    /** UUID người gửi. */
    @NonNull
    private String senderId;

    /** TEXT, IMAGE, AUDIO, VIDEO, FILE, LOCATION, SYSTEM hoặc CALL. */
    @NonNull
    private String type;

    /** Nội dung hoặc chú thích, null với tin không có chữ. */
    @Nullable
    private String text;

    /** Bản bỏ dấu phục vụ FTS4, luôn cập nhật cùng text. */
    @Nullable @ColumnInfo(name = "normalized_text")
    private String normalizedText;

    /** Metadata tệp do server cung cấp, không tự suy đoán. */
    @Nullable
    private String attachmentJson;

    /** Thời gian UTC mili-giây. */
    private long createdAt;

    /** SENDING, SENT, DELIVERED, READ hoặc FAILED. */
    @NonNull
    private String status;

    // Room dùng constructor rỗng để tạo đối tượng rồi đọc/ghi qua getter và setter.
    public MessageEntity() { }

    // Đọc và cập nhật localId khi ánh xạ DTO hoặc khôi phục cache.
    public long getLocalId() { return localId; }
    public void setLocalId(long value) { this.localId = value; }

    // Đọc và cập nhật serverId khi ánh xạ DTO hoặc khôi phục cache.
    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { this.serverId = value; }

    // Đọc và cập nhật clientId khi ánh xạ DTO hoặc khôi phục cache.
    public String getClientId() { return clientId; }
    public void setClientId(String value) { this.clientId = value; }

    // Đọc và cập nhật conversationId khi ánh xạ DTO hoặc khôi phục cache.
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { this.conversationId = value; }

    // Đọc và cập nhật senderId khi ánh xạ DTO hoặc khôi phục cache.
    public String getSenderId() { return senderId; }
    public void setSenderId(String value) { this.senderId = value; }

    // Đọc và cập nhật type khi ánh xạ DTO hoặc khôi phục cache.
    public String getType() { return type; }
    public void setType(String value) { this.type = value; }

    // Đọc và cập nhật text khi ánh xạ DTO hoặc khôi phục cache.
    public String getText() { return text; }
    public void setText(String value) { this.text = value; }

    // Đọc và cập nhật normalizedText khi ánh xạ DTO hoặc khôi phục cache.
    public String getNormalizedText() { return normalizedText; }
    public void setNormalizedText(String value) { this.normalizedText = value; }

    // Đọc và cập nhật attachmentJson khi ánh xạ DTO hoặc khôi phục cache.
    public String getAttachmentJson() { return attachmentJson; }
    public void setAttachmentJson(String value) { this.attachmentJson = value; }

    // Đọc và cập nhật createdAt khi ánh xạ DTO hoặc khôi phục cache.
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { this.createdAt = value; }

    // Đọc và cập nhật status khi ánh xạ DTO hoặc khôi phục cache.
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
}
