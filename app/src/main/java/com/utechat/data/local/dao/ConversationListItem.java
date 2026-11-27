package com.utechat.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Embedded;
import com.utechat.data.local.entity.ConversationEntity;

/**
 * Bản chiếu tổng hợp một dòng hội thoại cho màn hình danh sách chat:
 * kết hợp thông tin hội thoại, đối tác (với chat 1-1) và tin nhắn cuối hợp lệ theo visibleFromMessageId.
 */
public class ConversationListItem {
    /** Thực thể hội thoại gốc được nhúng trực tiếp từ bảng conversation. */
    @Embedded
    public ConversationEntity conversation;

    /** UUID của đối phương trong hội thoại DIRECT (null với nhóm). */
    @Nullable
    public String peerUserId;

    /** Tên hiển thị của đối phương từ bảng user_cache (dùng khi conversation.title là null). */
    @Nullable
    public String peerDisplayName;

    /** Ảnh đại diện của đối phương từ bảng user_cache. */
    @Nullable
    public String peerAvatarUrl;

    /** Loại tin nhắn cuối hợp lệ (TEXT, IMAGE, SYSTEM, CALL...). */
    @Nullable
    public String lastMessageType;

    /** Nội dung văn bản tin nhắn cuối hợp lệ. */
    @Nullable
    public String lastMessageText;

    /** Metadata JSON của tin nhắn cuối (dùng cho SYSTEM, CALL, đính kèm Phase 6). */
    @Nullable
    public String lastMessageAttachmentJson;

    /** UUID người gửi của tin nhắn cuối hợp lệ. */
    @Nullable
    public String lastMessageSenderId;

    /** Trạng thái tin nhắn cuối (SENDING, SENT, DELIVERED, READ, FAILED). */
    @Nullable
    public String lastMessageStatus;

    /** Server ID của tin nhắn cuối (null nếu là tin cục bộ đang gửi hoặc lỗi). */
    @Nullable
    public Long lastMessageServerId;

    /** Mốc gỡ để preview hiển thị trạng thái của tin cuối. */
    @Nullable
    public Long lastMessageRecalledAt;

    /** Trả về tên hội thoại hiển thị: ưu tiên tiêu đề nhóm, nếu trống thì dùng tên đối phương 1-1. */
    @NonNull
    public String getEffectiveTitle() {
        if (conversation != null && conversation.getTitle() != null && !conversation.getTitle().trim().isEmpty()) {
            return conversation.getTitle();
        }
        return peerDisplayName != null ? peerDisplayName : "";
    }

    /** Trả về URL ảnh đại diện hiệu lực: ưu tiên ảnh hội thoại, với chat 1-1 thì lấy ảnh đối phương. */
    @Nullable
    public String getEffectiveAvatarUrl() {
        if (conversation != null && conversation.getAvatarUrl() != null && !conversation.getAvatarUrl().trim().isEmpty()) {
            return conversation.getAvatarUrl();
        }
        if (conversation != null && "DIRECT".equals(conversation.getType())) {
            return peerAvatarUrl;
        }
        return null;
    }
}
