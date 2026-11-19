package com.utechat.data.repository;

import static org.junit.Assert.*;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.repository.fake.*;
import org.junit.*;

/** Mục 14: cùng nghiệp vụ giả nhưng cache và callback không vượt ranh giới phiên. */
public class FakeContactsSessionTest {
    @Rule public InstantTaskExecutorRule instant=new InstantTaskExecutorRule();
    private FakeAuthRepository auth;
    private FakeContactsRepository contacts;
    @Before public void setup() {
        auth=new FakeAuthRepository(); contacts=new FakeContactsRepository(auth);
        auth.getCurrentUser().observeForever(user->contacts.onSessionChanged());
        login("+84901111111");
    }
    private void login(String phone) { assertTrue(auth.login(phone,"fixture").getValue().isSuccess()); }
    @Test public void sameAccountReloginChangesSessionAndRejectsOldWrite() {
        String key=contacts.sessionKey(); auth.logout(); login("+84901111111");
        assertNotEquals(key,contacts.sessionKey());
        assertFalse(auth.runIfCurrent(key,()->fail("Callback cũ")));
    }
    @Test public void logoutClearsPreviouslyPublishedLists() {
        var old=contacts.getFriends(DemoData.ALICE); assertEquals(2,old.getValue().size());
        auth.logout(); assertTrue(old.getValue().isEmpty()); assertNull(contacts.sessionKey());
        assertEquals("UNAUTHENTICATED",contacts.refresh().getValue().errorCode);
    }
    @Test public void otherAccountCannotReadOldOwnerLists() {
        assertNull(contacts.getBlockedUsers(DemoData.ALICE).getValue().get(0).getPhone());
        login("+84902222222");
        assertTrue(contacts.getFriends(DemoData.ALICE).getValue().isEmpty());
        assertTrue(contacts.getBlockedUsers(DemoData.ALICE).getValue().isEmpty());
    }
    @Test public void signedOutCannotLookupOrMutate() {
        auth.logout(); assertEquals("UNAUTHENTICATED",contacts.lookup("+84902222222").getValue().errorCode);
        assertEquals("UNAUTHENTICATED",contacts.act("block",DemoData.BOB,null).getValue().errorCode);
    }
    @Test public void blockAndUnblockKeepFriendshipUntilUnfriend() {
        contacts.act("block",DemoData.BOB,null); contacts.act("unblock",DemoData.BOB,null);
        assertEquals("FRIEND",contacts.lookup("+84902222222").getValue().data.relationshipStatus);
        contacts.act("block",DemoData.BOB,null); contacts.act("unfriend",DemoData.BOB,null);
        contacts.act("unblock",DemoData.BOB,null);
        assertEquals("STRANGER",contacts.lookup("+84902222222").getValue().data.relationshipStatus);
    }
    @Test public void cancelCannotBeAcceptedAndDuplicateSendKeepsDirection() {
        assertTrue(contacts.act("send",DemoData.DAVID,null).getValue().isSuccess());
        var duplicate=contacts.act("send",DemoData.DAVID,null).getValue();
        assertEquals("REQUEST_ALREADY_PENDING",duplicate.errorCode); assertEquals("OUTGOING",duplicate.direction);
        assertTrue(contacts.act("cancel",duplicate.existingRequestId,null).getValue().isSuccess());
        login("+84904444444");
        assertEquals("INVALID_STATE",contacts.act("accept",duplicate.existingRequestId,null).getValue().errorCode);
    }
    @Test public void blockCancelsPendingAndUnblockDoesNotRestoreIt() {
        contacts.act("send",DemoData.DAVID,null);
        contacts.act("block",DemoData.DAVID,null); contacts.act("unblock",DemoData.DAVID,null);
        assertEquals("STRANGER",contacts.lookup("+84904444444").getValue().data.relationshipStatus);
    }
    @Test public void acceptOfHistoricalRequestAfterUnfriendDoesNotRestoreFriendship() {
        contacts.act("send",DemoData.DAVID,null);
        String id=contacts.lookup("+84904444444").getValue().data.pendingRequestId;
        login("+84904444444"); contacts.act("accept",id,null); contacts.act("unfriend",DemoData.ALICE,null);
        assertTrue(contacts.act("accept",id,null).getValue().isSuccess());
        assertEquals("STRANGER",contacts.lookup("+84901111111").getValue().data.relationshipStatus);
    }
}
