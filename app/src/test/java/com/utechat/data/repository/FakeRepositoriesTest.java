package com.utechat.data.repository;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.repository.fake.*;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/** Kiểm thử dữ liệu giả và bảo đảm không nhầm chúng với xác thực thật. */
public class FakeRepositoriesTest {
    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    @Test public void loginUsesExactPhoneAndLogoutClearsSession() {
        FakeAuthRepository auth = new FakeAuthRepository();
        assertEquals(DemoData.ALICE, auth.login("+84901111111", "demo").getValue().data.getId());
        auth.logout();
        assertNull(auth.getCurrentUser().getValue());
        assertEquals("BAD_CREDENTIALS", auth.login("0901111111", "demo").getValue().errorCode);
        assertEquals(5, DemoData.users().size());
    }

    @Test public void registerFlowRejectsTakenPhoneAndBadOtp() {
        FakeAuthRepository auth = new FakeAuthRepository();
        assertEquals("PHONE_TAKEN", auth.requestRegisterOtp("+84901111111").getValue().errorCode);
        OtpChallenge challenge = auth.requestRegisterOtp("+84912345678").getValue().data;
        assertEquals(FakeAuthRepository.DEMO_OTP, challenge.demoCode);
        assertEquals("OTP_INVALID", auth.verifyRegisterOtp(challenge.challengeId, "000000").getValue().errorCode);
        String token = auth.verifyRegisterOtp(challenge.challengeId, FakeAuthRepository.DEMO_OTP).getValue().data;
        assertEquals("+84912345678", auth.completeRegister(token, "Khang", "abcd1234").getValue().data.getPhone());
        assertEquals("Khang", auth.getCurrentUser().getValue().getDisplayName());
    }

    @Test public void resetFlowWorksForKnownPhoneAndNeverSucceedsForUnknown() {
        FakeAuthRepository auth = new FakeAuthRepository();
        // Số lạ vẫn nhận phiên OTP (không lộ số tồn tại) nhưng xác minh luôn thất bại.
        assertTrue(auth.requestResetOtp("+84912345678").getValue().isSuccess());
        assertEquals("OTP_INVALID", auth.verifyResetOtp("c", FakeAuthRepository.DEMO_OTP).getValue().errorCode);
        // Số có tài khoản: OTP đúng thì đặt lại và đăng nhập luôn.
        auth.requestResetOtp("+84901111111");
        String token = auth.verifyResetOtp("c", FakeAuthRepository.DEMO_OTP).getValue().data;
        assertEquals(DemoData.ALICE, auth.completePasswordReset(token, "abcd1234").getValue().data.getId());
        assertEquals(DemoData.ALICE, auth.getCurrentUser().getValue().getId());
    }

    @Test public void profileDevicesAndPasswordFollowTheSession() {
        FakeAuthRepository auth = new FakeAuthRepository();
        assertNull(auth.getProfile().getValue()); // Chưa đăng nhập thì chưa có hồ sơ.
        auth.login("+84901111111", "x");
        UserProfile me = auth.getProfile().getValue();
        assertEquals(DemoData.ALICE, me.id);
        // Sửa tên và cờ riêng tư phản ánh ngay vào hồ sơ.
        assertEquals("Alice A", auth.updateProfile("Alice A", false, false).getValue().data.displayName);
        assertFalse(auth.getProfile().getValue().discoverable);
        // Đổi mật khẩu: sai mật khẩu hiện tại bị từ chối, đúng thì mật khẩu mới có hiệu lực.
        assertEquals("BAD_CREDENTIALS", auth.changePassword("sai", "abcd1234").getValue().errorCode);
        assertTrue(auth.changePassword(FakeAuthRepository.DEMO_PASSWORD, "abcd1234").getValue().isSuccess());
        assertEquals("BAD_CREDENTIALS", auth.changePassword(FakeAuthRepository.DEMO_PASSWORD, "zzzz1234").getValue().errorCode);
        // Thiết bị: có 3, thu hồi được thiết bị khác nhưng không thu hồi được thiết bị hiện tại.
        assertEquals(3, auth.getDevices().getValue().data.size());
        assertEquals("NOT_FOUND", auth.revokeDevice("d1").getValue().errorCode);
        assertTrue(auth.revokeDevice("d2").getValue().isSuccess());
        assertEquals(2, auth.getDevices().getValue().data.size());
        auth.logout();
        assertNull(auth.getProfile().getValue());
    }

    @Test public void contactsRespectDirectionAndPrivacyFixture() {
        FakeContactsRepository contacts = new FakeContactsRepository();
        assertEquals(2, contacts.getFriends(DemoData.ALICE).getValue().size());
        assertEquals(DemoData.EVE, contacts.getBlockedUsers(DemoData.ALICE).getValue().get(0).getId());
        assertTrue(contacts.getBlockedUsers(DemoData.EVE).getValue().isEmpty());
        assertTrue(contacts.getFriends(DemoData.DAVID).getValue().isEmpty());
        assertFalse(contacts.searchByPhone("+84904444444").getValue().getAllowMessagesFromNonContacts());
    }

    @Test public void sendingPublishesNewMessageAndRejectsNonmembers() {
        FakeChatRepository chats = new FakeChatRepository();
        assertEquals(2, chats.getConversations(DemoData.ALICE).getValue().size());
        assertEquals(1, chats.getConversations(DemoData.CHARLIE).getValue().size());
        // Quan sát thật để phát hiện trường hợp object đổi nhưng LiveData không phát sự kiện.
        java.util.concurrent.atomic.AtomicInteger emissions = new java.util.concurrent.atomic.AtomicInteger();
        androidx.lifecycle.LiveData<java.util.List<com.utechat.data.local.entity.ConversationEntity>> stream =
                chats.getConversations(DemoData.ALICE);
        androidx.lifecycle.Observer<java.util.List<com.utechat.data.local.entity.ConversationEntity>> observer =
                value -> emissions.incrementAndGet();
        stream.observeForever(observer);
        int before = chats.getMessages(DemoData.DIRECT).getValue().size();
        chats.sendMessage(DemoData.DIRECT, DemoData.BOB, "Xin chào!");
        assertEquals(2, emissions.get());
        stream.removeObserver(observer);
        assertEquals(before + 1, chats.getMessages(DemoData.DIRECT).getValue().size());
        assertThrows(IllegalArgumentException.class,
                () -> chats.sendMessage(DemoData.DIRECT, DemoData.EVE, "Không phải thành viên"));
    }
}
