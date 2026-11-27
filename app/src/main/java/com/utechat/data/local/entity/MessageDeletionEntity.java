package com.utechat.data.local.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** ID của tin đã xóa phía tài khoản hiện tại để bỏ qua lịch sử hoặc ACK cũ đến sau. */
@Entity(tableName = "message_deletion")
public class MessageDeletionEntity {
    // ID vẫn tồn tại khi dòng tin đã được xóa, chỉ dọn khi chuyển tài khoản.
    @PrimaryKey
    public final long serverId;

    public MessageDeletionEntity(long serverId) {
        this.serverId = serverId;
    }
}
