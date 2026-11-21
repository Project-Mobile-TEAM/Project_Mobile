package com.utechat.ui.chat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.utechat.R;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.UserEntity;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Kiểm phần logic thuần của 05-07: câu SYSTEM, xem trước tin cuối, tô từ khóa, lọc tên và nhận diện SĐT. */
public class ChatTextTest {
    // Giả Context::getString: trả "id|tham số" để so mà không cần Android.
    private static final SystemMessageFormatter.Strings S = (id, args) -> {
        StringBuilder b = new StringBuilder(String.valueOf(id));
        for (Object a : args) b.append('|').append(a);
        return b.toString();
    };

    @Test public void memberAddedUsesDisplayNamesAndYou() {
        UserEntity an = new UserEntity(); an.setId("u-an"); an.setDisplayName("An");
        String json = "{\"systemEvent\":\"MEMBER_ADDED\",\"systemParams\":\"{\\\"actorId\\\":\\\"me\\\",\\\"targetUserIds\\\":[\\\"u-an\\\"]}\"}";
        String out = SystemMessageFormatter.format(S, json, Map.of("u-an", an), "me");
        assertEquals(R.string.chat_sys_member_added + "|" + R.string.chat_you + "|An", out);
    }

    @Test public void malformedSystemJsonFallsBack() {
        assertEquals(String.valueOf(R.string.chat_sys_unknown), SystemMessageFormatter.format(S, "{not json", null, "me"));
    }

    @Test public void previewPrefixesOwnMessagesAndLabelsMedia() {
        assertEquals(R.string.chat_preview_me + "|hi", SystemMessageFormatter.preview(S, "TEXT", "hi", null, "me", false, null, "me"));
        assertEquals(String.valueOf(R.string.chat_type_image), SystemMessageFormatter.preview(S, "IMAGE", null, null, "other", false, null, "me"));
    }

    @Test public void matchRangesIgnoresAccentsAndCase() {
        List<int[]> r = GlobalSearchAdapter.matchRanges("Gửi Báo cáo nhé", "bao cao");
        assertEquals(2, r.size());
        assertArrayEquals(new int[]{4, 7}, r.get(0));
        assertArrayEquals(new int[]{8, 11}, r.get(1));
        assertTrue(GlobalSearchAdapter.matchRanges("abc", " ").isEmpty());
    }

    @Test public void filterTitlesMatchesWithoutAccents() {
        ConversationListItem item = new ConversationListItem();
        item.conversation = new ConversationEntity();
        item.conversation.setId("c1");
        item.peerDisplayName = "Nguyễn Hoài An";
        assertEquals(1, ChatListViewModel.filterTitles(List.of(item), "hoai").size());
        assertEquals(0, ChatListViewModel.filterTitles(List.of(item), "binh").size());
    }

    @Test public void phoneOfOnlyAcceptsPhoneNumbers() {
        assertEquals("+84912345678", NewChatViewModel.phoneOf("0912 345 678"));
        assertNull(NewChatViewModel.phoneOf("An"));
        assertNull(NewChatViewModel.phoneOf("0912"));
    }
}
