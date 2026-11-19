package com.utechat.ui.contacts;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.*;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.*;
import com.utechat.data.remote.model.ContactDtos.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

/** Mục 15: state, một tác vụ/observer, sự kiện một lần và guard phiên; không dùng sleep cho 429. */
public class ContactsViewModelTest {
    @Rule public InstantTaskExecutorRule instant=new InstantTaskExecutorRule();
    private ContactsRepository repo;
    private SavedStateHandle state;
    private ContactsViewModel vm;
    private final AtomicReference<String> key=new AtomicReference<>("session-1");
    private final AtomicLong now=new AtomicLong(1000);
    private final MutableLiveData<String> sessions=new MutableLiveData<>("session-1");
    private final MutableLiveData<List<UserEntity>> friends=new MutableLiveData<>(List.of());
    private final MutableLiveData<Integer> count=new MutableLiveData<>(1);
    @Before public void setup() {
        repo=mock(ContactsRepository.class);
        when(repo.sessionKey()).thenAnswer(i->key.get());when(repo.currentUserId()).thenReturn("A");
        when(repo.sessionChanges()).thenReturn(sessions);
        when(repo.getFriends("A")).thenReturn(friends);when(repo.getBlockedUsers("A")).thenReturn(new MutableLiveData<>(List.of()));
        when(repo.getRequests()).thenReturn(new MutableLiveData<>(List.of()));when(repo.incomingCount("A")).thenReturn(count);
        when(repo.refresh()).thenReturn(new MutableLiveData<>(ContactsResult.ok(true)));
        when(repo.lookup(anyString())).thenAnswer(i->new MutableLiveData<ContactsResult<Lookup>>());
        when(repo.act(anyString(),anyString(),any())).thenReturn(new MutableLiveData<>(ContactsResult.ok(true)));
        state=new SavedStateHandle();vm=new ContactsViewModel(state,repo,now::get);
    }
    private Lookup user(String status) {
        Lookup u=new Lookup();u.id="B";u.displayName="Bob";u.relationshipStatus=status;return u;
    }
    private void changeSession() { key.set("session-2");sessions.setValue(key.get()); }
    private void phone() { state.set("phone","0901111111"); }
    @After public void cleanup() { vm.onCleared(); }

    // Tải lại và lifecycle: không tạo observer/tác vụ trùng khi recreate.
    @Test public void ensureLoadedRunsOnlyOnce() { vm.ensureLoaded();vm.ensureLoaded();verify(repo,times(1)).refresh(); }
    @Test public void loadingAndOperationRemainUntilResponse() {
        MutableLiveData<ContactsResult<Boolean>> pending=new MutableLiveData<>();when(repo.refresh()).thenReturn(pending);
        vm.refresh();assertTrue(vm.busy.getValue());assertEquals("refresh",vm.operation.getValue());
        pending.setValue(ContactsResult.ok(true));assertFalse(vm.busy.getValue());assertNull(vm.operation.getValue());assertFalse(pending.hasObservers());
    }
    @Test public void busyPreventsDuplicateRefreshAndMutation() {
        when(repo.refresh()).thenReturn(new MutableLiveData<>());vm.refresh();vm.refresh();vm.act("block","B",null);
        verify(repo,times(1)).refresh();verify(repo,never()).act(anyString(),anyString(),any());
    }
    @Test public void synchronousResponseObserverIsRemoved() {
        MutableLiveData<ContactsResult<Boolean>> response=new MutableLiveData<>(ContactsResult.ok(true));when(repo.refresh()).thenReturn(response);
        vm.refresh();assertFalse(response.hasObservers());assertFalse(vm.busy.getValue());
    }
    @Test public void clearedViewModelDetachesPendingAndSessionObservers() {
        MutableLiveData<ContactsResult<Boolean>> pending=new MutableLiveData<>();when(repo.refresh()).thenReturn(pending);
        vm.friends.observeForever(value->{});vm.refresh();vm.onCleared();assertFalse(pending.hasObservers());assertFalse(sessions.hasObservers());assertFalse(friends.hasObservers());
        pending.setValue(ContactsResult.error("NETWORK"));assertNull(vm.notice.getValue());
    }
    @Test public void networkFailureKeepsCachedFriendsAndOffersError() {
        UserEntity u=new UserEntity();u.setId("B");u.setDisplayName("Bob");friends.setValue(List.of(u));
        vm.friends.observeForever(value->{});
        when(repo.refresh()).thenReturn(new MutableLiveData<>(ContactsResult.error("NETWORK")));vm.refresh();
        assertEquals(1,vm.friends.getValue().size());assertEquals("NETWORK",vm.listError.getValue().errorCode);assertFalse(vm.busy.getValue());
    }
    @Test public void successfulRefreshClearsEarlierError() {
        when(repo.refresh()).thenReturn(new MutableLiveData<>(ContactsResult.error("NETWORK")),new MutableLiveData<>(ContactsResult.ok(true)));
        vm.refresh();vm.consumeNotice();vm.refresh();assertNull(vm.listError.getValue());assertFalse(vm.needsRefresh.getValue());
    }

    // SavedStateHandle giữ nhập liệu, bộ lọc và tab cho đúng phiên.
    @Test public void restoredInputsStayForSameSession() {
        state.set("phone","0901111111");state.set("filter","Bob");state.set("tab",1);
        Map<String,Object> saved=new HashMap<>();for(String k:state.keys()) saved.put(k,state.get(k));
        ContactsViewModel restored=new ContactsViewModel(new SavedStateHandle(saved),repo,now::get);
        assertEquals("0901111111",restored.phone());assertEquals("Bob",restored.filter());assertEquals(1,restored.tab());restored.onCleared();
    }
    @Test public void savedInputsFromOtherSessionAreCleared() {
        Map<String,Object> saved=new HashMap<>();saved.put("contacts_session","old");saved.put("phone","secret input");saved.put("filter","old");saved.put("tab",1);
        ContactsViewModel restored=new ContactsViewModel(new SavedStateHandle(saved),repo,now::get);
        assertEquals("",restored.phone());assertEquals("",restored.filter());assertEquals(0,restored.tab());restored.onCleared();
    }
    @Test public void initialEmptySessionSignalDoesNotRejectPersistedIdentity() {
        sessions.setValue(null);
        ContactsViewModel restored=new ContactsViewModel(new SavedStateHandle(),repo,now::get);
        assertTrue(restored.sessionActive.getValue());restored.refresh();verify(repo).refresh();restored.onCleared();
    }
    @Test public void invalidPhoneNeverCallsNetwork() {
        state.set("phone","abc");vm.search();assertEquals("PHONE_INVALID",vm.notice.getValue().errorCode);verify(repo,never()).lookup(anyString());
    }
    @Test public void phoneInputIsNormalizedBeforeLookup() { phone();vm.search();verify(repo).lookup("+84901111111"); }

    // Năm trạng thái chỉ định hành động hợp lệ, không có nút nhắn tin.
    @Test public void allFiveRelationshipsExposeExpectedActions() {
        Map<String,List<String>> expected=Map.of("STRANGER",List.of("send","block"),"FRIEND",List.of("unfriend","block"),
                "OUTGOING_PENDING",List.of("cancel","block"),"INCOMING_PENDING",List.of("accept","reject","block"),"BLOCKED",List.of("unblock"));
        expected.forEach((status,actions)->{vm.lookup.setValue(user(status));assertEquals(actions,vm.availableActions());assertFalse(vm.availableActions().contains("message"));});
    }
    @Test public void unknownRelationshipDoesNotGuessActions() { vm.lookup.setValue(user("UNKNOWN"));assertTrue(vm.availableActions().isEmpty()); }
    @Test public void incoming409UsesExistingIdWithoutAutoAccept() {
        Lookup original=user("STRANGER");vm.lookup.setValue(original);
        ContactsResult<Boolean> conflict=ContactsResult.error("REQUEST_ALREADY_PENDING");conflict.direction="INCOMING";conflict.existingRequestId="r1";
        when(repo.act("send","B",original)).thenReturn(new MutableLiveData<>(conflict));vm.act("send","B",original);
        assertEquals("INCOMING_PENDING",vm.lookup.getValue().relationshipStatus);assertEquals("r1",vm.lookup.getValue().pendingRequestId);
        assertTrue(vm.lookup.getValue().hasPendingRequest);assertNotSame(original,vm.lookup.getValue());assertEquals("STRANGER",original.relationshipStatus);
        verify(repo,times(1)).act(anyString(),anyString(),any());
    }
    @Test public void outgoing409KeepsCancelDirection() {
        Lookup u=user("STRANGER");vm.lookup.setValue(u);ContactsResult<Boolean> r=ContactsResult.error("REQUEST_ALREADY_PENDING");
        r.direction="OUTGOING";r.existingRequestId="r1";when(repo.act("send","B",u)).thenReturn(new MutableLiveData<>(r));vm.act("send","B",u);
        assertEquals(List.of("cancel","block"),vm.availableActions());
    }
    @Test public void incomplete409ClearsLookupAndRequiresRefresh() {
        vm.lookup.setValue(user("STRANGER"));when(repo.act(anyString(),anyString(),any())).thenReturn(new MutableLiveData<>(ContactsResult.error("REQUEST_ALREADY_PENDING")));
        vm.act("send","B",null);assertNull(vm.lookup.getValue());assertTrue(vm.needsRefresh.getValue());assertFalse(vm.canAct());
    }

    // 429 giữ thời hạn qua recreate; không gửi request lặp trong thời gian chờ.
    @Test public void rateLimitBlocksRetryUntilMonotonicDeadline() {
        phone();ContactsResult<Lookup> limited=ContactsResult.error("RATE_LIMITED");limited.retryAfterSeconds=5;
        when(repo.lookup(anyString())).thenReturn(new MutableLiveData<>(limited),new MutableLiveData<>(ContactsResult.ok(user("STRANGER"))));
        vm.search();now.addAndGet(1000);vm.search();assertEquals(4,vm.retrySeconds());verify(repo,times(1)).lookup(anyString());
        now.addAndGet(4000);vm.search();verify(repo,times(2)).lookup(anyString());assertEquals("B",vm.lookup.getValue().id);
    }
    @Test public void rateLimitDeadlineSurvivesHandleRestoration() {
        phone();ContactsResult<Lookup> limited=ContactsResult.error("RATE_LIMITED");limited.retryAfterSeconds=9;
        when(repo.lookup(anyString())).thenReturn(new MutableLiveData<>(limited));vm.search();
        ContactsViewModel restored=new ContactsViewModel(state,repo,now::get);assertEquals(9,restored.retrySeconds());restored.onCleared();
    }
    @Test public void changingPhoneDropsPendingLookupAndReleasesBusy() {
        phone();MutableLiveData<ContactsResult<Lookup>> pending=new MutableLiveData<>();when(repo.lookup(anyString())).thenReturn(pending);
        vm.search();state.set("phone","0902222222");assertFalse(pending.hasObservers());assertFalse(vm.busy.getValue());
        pending.setValue(ContactsResult.ok(user("STRANGER")));assertNull(vm.lookup.getValue());
    }

    // Đổi phiên xóa mọi dữ liệu cá nhân và hủy observer mà không đợi HTTP trả về.
    @Test public void sessionChangeImmediatelyClearsLookupInputsAndBusy() {
        phone();state.set("filter","Bob");vm.lookup.setValue(user("FRIEND"));vm.search();changeSession();
        assertNull(vm.lookup.getValue());assertEquals("",vm.phone());assertEquals("",vm.filter());assertFalse(vm.busy.getValue());assertFalse(vm.sessionActive.getValue());
    }
    @Test public void sameAccountNewSessionCannotReceiveOldRefresh() {
        MutableLiveData<ContactsResult<Boolean>> pending=new MutableLiveData<>();when(repo.refresh()).thenReturn(pending);
        vm.refresh();changeSession();pending.setValue(ContactsResult.error("NETWORK"));assertNull(vm.notice.getValue());assertFalse(vm.canAct());
    }
    @Test public void oldViewModelCannotActForNewSession() { changeSession();vm.act("block","B",null);verify(repo,never()).act(anyString(),anyString(),any()); }
    @Test public void cachedStreamIsClearedOnSessionChange() {
        UserEntity u=new UserEntity();u.setId("B");friends.setValue(List.of(u));vm.friends.observeForever(value->{});
        assertEquals(1,vm.friends.getValue().size());changeSession();assertTrue(vm.friends.getValue().isEmpty());
        friends.setValue(List.of(u));assertTrue(vm.friends.getValue().isEmpty());
    }

    // Sự kiện và mutation: ACK không tự tạo trạng thái quan hệ, refresh lỗi không cho gửi lại.
    @Test public void noticeCanBeConsumedOnlyOnce() {
        when(repo.refresh()).thenReturn(new MutableLiveData<>(ContactsResult.error("NETWORK")));vm.refresh();
        assertEquals("NETWORK",vm.consumeNotice().errorCode);assertNull(vm.consumeNotice());assertNull(vm.notice.getValue());
    }
    @Test public void confirmedMutationDoesNotInferRelationship() {
        vm.lookup.setValue(user("FRIEND"));vm.act("unfriend","B",null);assertNull(vm.lookup.getValue());assertTrue(vm.notice.getValue().isSuccess());
    }
    @Test public void confirmedButStaleResultBlocksRepeatUntilRefresh() {
        ContactsResult<Boolean> r=ContactsResult.ok(true);r.refreshRequired=true;when(repo.act(anyString(),anyString(),any())).thenReturn(new MutableLiveData<>(r));
        vm.act("block","B",null);assertTrue(vm.needsRefresh.getValue());assertTrue(vm.notice.getValue().isSuccess());
        vm.act("block","B",null);verify(repo,times(1)).act(anyString(),anyString(),any());vm.refresh();assertTrue(vm.canAct());
    }
    @Test public void uncertainMutationIsNotConvertedToSuccessOrRetried() {
        ContactsResult<Boolean> r=ContactsResult.error("NETWORK");r.refreshRequired=true;when(repo.act(anyString(),anyString(),any())).thenReturn(new MutableLiveData<>(r));
        vm.act("block","B",null);assertFalse(vm.notice.getValue().isSuccess());assertTrue(vm.needsRefresh.getValue());assertNull(vm.lookup.getValue());
        vm.act("block","B",null);verify(repo,times(1)).act(anyString(),anyString(),any());
    }
    @Test public void rejectedMutationKeepsCurrentRelationship() {
        Lookup u=user("FRIEND");vm.lookup.setValue(u);when(repo.act(anyString(),anyString(),any())).thenReturn(new MutableLiveData<>(ContactsResult.error("FORBIDDEN")));
        vm.act("unfriend","B",u);assertSame(u,vm.lookup.getValue());assertFalse(vm.needsRefresh.getValue());
    }
    @Test public void invalidActionDoesNotReachRepository() {
        vm.act("unknown","B",null);assertEquals("VALIDATION",vm.notice.getValue().errorCode);verify(repo,never()).act(anyString(),anyString(),any());
    }
    @Test public void badgeUsesIncomingCountStreamRatherThanAllRequests() {
        vm.count.observeForever(value->{});assertEquals(Integer.valueOf(1),vm.count.getValue());count.setValue(0);assertEquals(Integer.valueOf(0),vm.count.getValue());
    }
}
