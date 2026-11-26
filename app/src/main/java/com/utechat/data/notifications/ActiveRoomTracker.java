package com.utechat.data.notifications;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Bộ theo dõi an toàn đa luồng lưu mã hội thoại đang hiển thị trên ChatRoomFragment (D-05):
 * dùng clearIfMatches(...) trong onPause để không xóa nhầm phòng chat mới mở khi chuyển trực tiếp giữa 2 phòng.
 */
public final class ActiveRoomTracker {
    @Nullable
    private volatile String activeConversationId;

    /** Đánh dấu phòng chat đang mở trên màn hình (gọi từ onResume). */
    public synchronized void setActive(@Nullable String conversationId) {
        this.activeConversationId = conversationId;
    }

    /** Chỉ xóa trạng thái đang mở nếu đúng phòng vừa rời đi (gọi từ onPause). */
    public synchronized void clearIfMatches(@Nullable String conversationId) {
        if (conversationId != null && Objects.equals(this.activeConversationId, conversationId)) {
            this.activeConversationId = null;
        }
    }

    /** Trả về conversationId đang hiển thị trước mặt người dùng, hoặc null nếu không ở trong phòng chat nào. */
    @Nullable
    public String getActiveConversationId() {
        return activeConversationId;
    }

    /** Xóa sạch trạng thái phòng đang mở (ví dụ khi đăng xuất hoặc đổi tài khoản). */
    public synchronized void clear() {
        this.activeConversationId = null;
    }
}
