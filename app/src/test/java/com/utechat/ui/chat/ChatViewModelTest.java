package com.utechat.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.utechat.R;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.media.MediaPreparationHelper;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.data.translate.MessageTranslationManager;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

/** Kiểm logic ViewModel của 05-08/05-09 trên JVM: xoay màn hình, báo đọc, biểu ngữ, tạo nhóm, rời nhóm, kiểu tin. */
public class ChatViewModelTest {
    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private ChatRepository repo() {
        ChatRepository r = mock(ChatRepository.class);
        when(r.currentUserId()).thenReturn("me");
        when(r.observeVisibleMessages(anyString(), anyInt())).thenReturn(new MutableLiveData<>(List.of()));
        return r;
    }

    @Test public void roomKeepsStateAndExpandsWindowForOldTarget() {
        ChatRepository r = repo();
        when(r.computeWindowLimitForMessage("c1", 7L, ChatRoomViewModel.PAGE)).thenReturn(new MutableLiveData<>(120));
        SavedStateHandle state = new SavedStateHandle(Map.of("conversationId", "c1", "targetMessageLocalId", 7L));
        ChatRoomViewModel vm = new ChatRoomViewModel(state, r, mock(ContactsRepository.class));
        // Tin đích nằm ngoài 50 tin mới nhất: cửa sổ nới tới số repository tính ra, và đích chờ cuộn.
        assertEquals(Integer.valueOf(120), state.get("windowLimit"));
        assertEquals(Long.valueOf(7L), vm.pendingScroll.getValue());
        // Tạo lại ViewModel (xoay màn hình) với cùng state: không cuộn tới đích lần nữa.
        ChatRoomViewModel again = new ChatRoomViewModel(state, r, mock(ContactsRepository.class));
        assertNull(again.pendingScroll.getValue());
        verify(r, times(1)).computeWindowLimitForMessage("c1", 7L, ChatRoomViewModel.PAGE);
    }

    @Test public void readReceiptOnlyWhenResumedAndForVisibleWatermark() {
        ChatRepository r = repo();
        ChatRoomViewModel vm = new ChatRoomViewModel(new SavedStateHandle(Map.of("conversationId", "c1")), r, mock(ContactsRepository.class));
        vm.markVisibleRead(102, false, false); // Đang PAUSED.
        vm.markVisibleRead(102, true, true);   // Đã rời nhóm.
        verify(r, never()).markConversationRead(anyString(), anyLong());
        vm.markVisibleRead(102, true, false);
        vm.markVisibleRead(101, true, false);  // Cuộn lên: không gửi mốc thấp hơn.
        vm.markVisibleRead(102, true, false);  // Layout lặp: không gửi trùng.
        verify(r, times(1)).markConversationRead("c1", 102);
    }

    @Test public void deepLinkPolicyErrorAndFailedMessageShowBanner() {
        ChatRepository r = repo();
        when(r.openOrCreateDirect("peer")).thenReturn(new MutableLiveData<>(ChatResult.error("BLOCKED")));
        ChatRoomViewModel vm = new ChatRoomViewModel(new SavedStateHandle(Map.of("peerUserId", "peer")), r, mock(ContactsRepository.class));
        assertEquals("BLOCKED", ChatRoomViewModel.policyBanner(vm.openError.getValue(), null, "me"));

        MessageEntity failed = msg("me", "TEXT", "FAILED");
        failed.setErrorCode("CONTACT_REQUIRED");
        assertEquals("CONTACT_REQUIRED", ChatRoomViewModel.policyBanner(null, List.of(failed), "me"));
        // Gửi thành công một tin sau đó thì biểu ngữ biến mất.
        assertNull(ChatRoomViewModel.policyBanner(null, List.of(failed, msg("me", "TEXT", "SENT")), "me"));
    }

    @Test public void contactRequiredBannerSendsFriendRequestOnce() {
        ContactsRepository contacts = mock(ContactsRepository.class);
        when(contacts.act("send", "peer", null)).thenReturn(new MutableLiveData<>(
                com.utechat.data.repository.ContactsResult.ok(true)));
        ChatRoomViewModel vm = new ChatRoomViewModel(new SavedStateHandle(Map.of("conversationId", "c1")), repo(), contacts);
        String peer = ChatRoomViewModel.peerOf(null, List.of(member("me", "MEMBER", false), member("peer", "MEMBER", false)), "me");
        vm.sendFriendRequest(peer);
        vm.sendFriendRequest(peer); // Bấm lại sau khi đã gửi: không gửi lời mời thứ hai.
        assertTrue(vm.requestSent.getValue());
        verify(contacts, times(1)).act("send", "peer", null);
    }

    @Test public void leftMemberIsReadOnly() {
        ConversationMemberEntity me = member("me", "MEMBER", true);
        assertTrue(ChatRoomViewModel.isReadOnly(List.of(me), "me"));
        assertFalse(ChatRoomViewModel.isReadOnly(List.of(member("me", "MEMBER", false)), "me"));
    }

    @Test public void groupCreateNeedsTitleAndTwoToFortyNineFriends() {
        assertEquals(R.string.group_name_error, GroupCreateViewModel.validate("  ", 3));
        assertEquals(R.string.group_name_error, GroupCreateViewModel.validate("x".repeat(51), 3));
        assertEquals(R.string.group_min_members, GroupCreateViewModel.validate("Đồ án", 1));
        assertEquals(R.string.chat_error_group_full, GroupCreateViewModel.validate("Đồ án", 50));
        assertEquals(0, GroupCreateViewModel.validate("Đồ án", 49));
    }

    @Test public void soleAdminMustPickSuccessorOnlyWhenOthersRemain() {
        List<ConversationMemberEntity> sole = List.of(member("me", "ADMIN", false), member("an", "MEMBER", false));
        assertTrue(GroupInfoViewModel.needsSuccessor(sole, "me"));
        List<ConversationMemberEntity> twoAdmins = List.of(member("me", "ADMIN", false), member("an", "ADMIN", false));
        assertFalse(GroupInfoViewModel.needsSuccessor(twoAdmins, "me"));
        List<ConversationMemberEntity> last = List.of(member("me", "ADMIN", false), member("an", "MEMBER", true));
        assertFalse(GroupInfoViewModel.needsSuccessor(last, "me"));
    }

    @Test public void messageAdapterMapsAllSixViewTypesAndKeepsMetadata() {
        assertEquals(MessageAdapter.TYPE_SYSTEM, MessageAdapter.viewType(msg("x", "SYSTEM", "SENT"), "me"));
        assertEquals(MessageAdapter.TYPE_TEXT_OUT, MessageAdapter.viewType(msg("me", "TEXT", "SENT"), "me"));
        assertEquals(MessageAdapter.TYPE_TEXT_IN, MessageAdapter.viewType(msg("an", "TEXT", "SENT"), "me"));
        for (String t : List.of("IMAGE", "AUDIO", "VIDEO", "FILE", "LOCATION", "CALL")) {
            MessageEntity in = msg("an", t, "SENT");
            in.setAttachmentJson("{\"fileName\":\"a.docx\"}");
            assertEquals(MessageAdapter.NON_TEXT_SUMMARY_IN, MessageAdapter.viewType(in, "me"));
            assertEquals(MessageAdapter.NON_TEXT_SUMMARY_OUT, MessageAdapter.viewType(msg("me", t, "SENT"), "me"));
            assertEquals(t, in.getType()); // Không hạ cấp loại tin.
            assertEquals("{\"fileName\":\"a.docx\"}", in.getAttachmentJson());
        }
        assertEquals(MessageAdapter.UNKNOWN_FALLBACK, MessageAdapter.viewType(msg("an", "FUTURE_STICKER", "SENT"), "me"));
        assertEquals("a.docx", MessageAdapter.summaryMeta("FILE", "{\"fileName\":\"a.docx\"}"));
        assertEquals("1:05", MessageAdapter.summaryMeta("AUDIO", "{\"durationMs\":65000}"));
        assertEquals("10.7626, 106.6602", MessageAdapter.summaryMeta("LOCATION", "{\"latitude\":10.76262,\"longitude\":106.66017}"));
        assertNull(MessageAdapter.summaryMeta("AUDIO", "{\"durationMs\":\"abc\"}"));
        assertNull(MessageAdapter.summaryMeta("FILE", "not json"));
    }

    @Test public void translationSelectionPersistsAcrossRotationViaSavedStateHandleAndHidesCleanly() {
        ChatRepository r = repo();
        MessageTranslationManager translator = mock(MessageTranslationManager.class);
        doAnswer(inv -> {
            MessageTranslationManager.Callback cb = inv.getArgument(3);
            cb.onUpdate(MessageTranslationManager.TranslationUpdate.translated("Xin chào", "vi"));
            return null;
        }).when(translator).translateMessage(any(MessageEntity.class), eq("vi"), anyBoolean(), any());

        SavedStateHandle state = new SavedStateHandle(Map.of("conversationId", "c1"));
        ChatRoomViewModel vm = new ChatRoomViewModel(
                state, r, mock(ContactsRepository.class), translator, null, null, null);

        MessageEntity m = msg("an", "TEXT", "SENT");
        m.setClientId("cid-trans-1");
        m.setText("Hello");

        vm.requestTranslate(m, "vi", false);
        Map<String, MessageAdapter.TranslationUiEntry> current = vm.getTranslations().getValue();
        assertNotNull(current);
        assertTrue(vm.hasActiveTranslation("cid-trans-1"));
        assertEquals("Xin chào", current.get("cid-trans-1").translatedText);

        // Xoay màn hình: tạo lại ViewModel với cùng SavedStateHandle thì bản dịch vẫn giữ nguyên (ADR-021).
        ChatRoomViewModel rotated = new ChatRoomViewModel(
                state, r, mock(ContactsRepository.class), translator, null, null, null);
        assertTrue(rotated.hasActiveTranslation("cid-trans-1"));
        assertEquals("Xin chào", rotated.getTranslations().getValue().get("cid-trans-1").translatedText);

        // Ẩn bản dịch xóa sạch cả LiveData lẫn SavedStateHandle (D-14).
        rotated.hideTranslation("cid-trans-1");
        assertFalse(rotated.hasActiveTranslation("cid-trans-1"));
        assertTrue(rotated.getTranslations().getValue().isEmpty());

        ChatRoomViewModel afterHide = new ChatRoomViewModel(
                state, r, mock(ContactsRepository.class), translator, null, null, null);
        assertFalse(afterHide.hasActiveTranslation("cid-trans-1"));
    }

    @Test public void enqueueMediaAndLocationDelegateToRepository() {
        ChatRepository r = repo();
        SavedStateHandle state = new SavedStateHandle(Map.of("conversationId", "c1"));
        ChatRoomViewModel vm = new ChatRoomViewModel(state, r, mock(ContactsRepository.class));

        MediaPreparationHelper.PreparedMedia media = new MediaPreparationHelper.PreparedMedia(
                "cid-media-1", "IMAGE", "/tmp/cid-media-1.jpg", "image/jpeg", 2048L, "photo.jpg", 800, 600, null);
        vm.enqueuePreparedMedia(media, "Ảnh lớp học");
        vm.enqueueLocation(10.8506, 106.7719);

        verify(r, times(1)).enqueueMediaMessage("c1", media, "Ảnh lớp học");
        verify(r, times(1)).enqueueLocationMessage("c1", 10.8506, 106.7719);
        verify(r, times(2)).sendTypingStop("c1");
    }

    private static MessageEntity msg(String sender, String type, String status) {
        MessageEntity m = new MessageEntity();
        m.setSenderId(sender); m.setType(type); m.setStatus(status);
        return m;
    }

    private static ConversationMemberEntity member(String id, String role, boolean left) {
        ConversationMemberEntity m = new ConversationMemberEntity();
        m.setUserId(id); m.setRole(role); m.setLeft(left);
        return m;
    }
}
