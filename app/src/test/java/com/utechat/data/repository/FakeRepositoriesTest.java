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
        assertEquals(DemoData.ALICE, auth.login("+84901111111", "demo").getValue().getId());
        auth.logout();
        assertNull(auth.getCurrentUser().getValue());
        assertNull(auth.login("0901111111", "demo").getValue());
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
