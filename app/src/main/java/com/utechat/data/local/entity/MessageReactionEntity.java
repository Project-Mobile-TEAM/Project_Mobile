package com.utechat.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Ignore;

/** Cảm xúc lưu cục bộ để mở được khi mất mạng. */
@Entity(tableName = "message_reaction", primaryKeys = {"messageServerId", "userId"},
        indices = {@Index("messageServerId")})
public class MessageReactionEntity {
    /** ID tin nhắn trên server. */
    private long messageServerId;

    /** ID người đã thả cảm xúc. */
    @NonNull
    private String userId = "";

    /** Emoji người dùng đang chọn. */
    @NonNull
    private String emoji = "";

    /** Mốc UTC mili-giây do server trả về. */
    private long createdAt;

    // Room tạo đối tượng rồi điền từng cột qua setter.
    public MessageReactionEntity() { }

    // Sự kiện đồng bộ chứa đầy đủ dữ liệu của một cảm xúc.
    @Ignore
    public MessageReactionEntity(long messageServerId, @NonNull String userId, @NonNull String emoji, long createdAt) {
        this.messageServerId = messageServerId;
        this.userId = userId;
        this.emoji = emoji;
        this.createdAt = createdAt;
    }

    public long getMessageServerId() { return messageServerId; }
    public void setMessageServerId(long value) { messageServerId = value; }

    @NonNull public String getUserId() { return userId; }
    public void setUserId(@NonNull String value) { userId = value; }

    @NonNull public String getEmoji() { return emoji; }
    public void setEmoji(@NonNull String value) { emoji = value; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { createdAt = value; }
}
