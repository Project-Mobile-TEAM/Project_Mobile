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
}
