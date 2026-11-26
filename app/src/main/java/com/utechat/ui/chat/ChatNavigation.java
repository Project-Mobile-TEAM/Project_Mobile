package com.utechat.ui.chat;

import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.NavDeepLinkRequest;
import com.utechat.R;

/**
 * Điểm vào duy nhất để mở phòng chat (D-02).
 * Màn Danh bạ (nav_contacts) gọi openDirectChat(navController, friendId) ở nút "Nhắn tin":
 * deep link đi xuyên graph con mà không cần global action trong nav_graph.xml.
 */
public final class ChatNavigation {
    private ChatNavigation() { }

    /** Mở chat 1-1 theo userId; ChatRoomFragment tự gọi openOrCreateDirect để lấy hội thoại. */
    public static void openDirectChat(@NonNull NavController navController, @NonNull String peerUserId) {
        // Deep link chỉ mang UUID người nhận, không bao giờ mang token.
        navController.navigate(NavDeepLinkRequest.Builder
                .fromUri(Uri.parse("utechat://chat/direct/" + Uri.encode(peerUserId))).build());
    }

    /** Mở hội thoại đã biết id (đang ở trong nav_chat); targetMessageLocalId = -1 nếu không cần cuộn tới tin nào. */
    public static void openConversation(@NonNull NavController navController, @NonNull String conversationId, long targetMessageLocalId) {
        Bundle args = new Bundle();
        args.putString("conversationId", conversationId);
        args.putLong("targetMessageLocalId", targetMessageLocalId);
        navController.navigate(R.id.chatRoomFragment, args);
    }

    /**
     * Mở phòng chat an toàn từ thông báo bất kể người dùng đang đứng ở màn nào (Danh bạ, Hồ sơ, Thiết bị, Phòng chat khác...):
     * quay về chatListFragment trước (hoặc chuyển sang nav_chat) rồi mới mở chatRoomFragment, tránh IllegalArgumentException.
     */
    public static void openConversationFromNotification(
            @NonNull NavController navController, @NonNull String conversationId) {
        androidx.navigation.NavDestination current = navController.getCurrentDestination();
        if (current == null || current.getId() != R.id.chatListFragment) {
            boolean popped = navController.popBackStack(R.id.chatListFragment, false);
            if (!popped) {
                navController.navigate(R.id.nav_chat);
                navController.popBackStack(R.id.chatListFragment, false);
            }
        }
        openConversation(navController, conversationId, -1L);
    }
}
