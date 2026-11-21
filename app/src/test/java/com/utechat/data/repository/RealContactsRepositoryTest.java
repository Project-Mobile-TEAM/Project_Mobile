package com.utechat.data.repository;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.*;
import com.utechat.data.local.dao.*;
import com.utechat.data.local.entity.*;
import com.utechat.data.remote.api.ContactApi;
import com.utechat.data.remote.model.ContactDtos.*;
import com.utechat.data.repository.real.RealContactsRepository;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import retrofit2.*;

/** Mục 12–13: điều khiển NETWORK/DISK/main để tái hiện callback cũ; không thay thế test Room thật. */
public class RealContactsRepositoryTest {
    @Rule public InstantTaskExecutorRule instant=new InstantTaskExecutorRule();
    private ContactDao contacts;
    private UserDao users;
    private ContactApi api;
    private Session session;
    private RealContactsRepository repo;
    private final MutableLiveData<List<UserEntity>> friends=new MutableLiveData<>(List.of());

    static class Session implements CurrentUserIdProvider {
        String owner="A",key="A-session-1";
        final MutableLiveData<String> changes=new MutableLiveData<>(key);
        public String getCurrentUserId() { return owner; }
        public String sessionKey() { return key; }
        public LiveData<String> sessionChanges() { return changes; }
        public synchronized boolean runIfCurrent(String expected,Runnable action) {
            if(expected==null||!Objects.equals(key,expected)) return false;
            action.run(); return true;
        }
        void change(String actor,String next) { owner=actor; key=next; changes.setValue(next); }
    }
    static class Queue implements Executor {
        final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
        public void execute(Runnable action) { jobs.add(action); }
        void drain() { while(!jobs.isEmpty()) jobs.remove().run(); }
    }
    @SuppressWarnings("unchecked") private static <T> Call<T> response(Response<T> value) throws IOException {
        return mock(Call.class,invocation -> "execute".equals(invocation.getMethod().getName())
                ? value : RETURNS_DEFAULTS.answer(invocation));
    }
    private static <T> Call<T> success(T value) throws IOException { return response(Response.success(value)); }
    @SuppressWarnings("unchecked") private static <T> Call<T> failure(Exception failure) throws IOException {
        return mock(Call.class,invocation -> {
            if("execute".equals(invocation.getMethod().getName())) throw failure;
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }
    private static <T> Call<T> http(int code,String json) throws IOException {
        return response(Response.error(code,okhttp3.ResponseBody.create(json,okhttp3.MediaType.get("application/json"))));
    }
    private static Contact contact(String id,boolean blocked) {
        Contact c=new Contact(); c.id=id; c.displayName=id; c.blocked=blocked; c.phone="+84901111111"; return c;
    }
    private static User user(String id) { User u=new User(); u.id=id; u.displayName=id; return u; }
    private static Request request(String status) {
        Request r=new Request(); r.id="r1"; r.requester=user("A"); r.addressee=user("B");
        r.status=status; r.createdAt="2026-11-19T00:00:00Z"; return r;
    }
    private static Lookup lookup(String id) {
        Lookup r=new Lookup(); r.id=id; r.displayName=id; r.relationshipStatus="STRANGER"; return r;
    }
    private RealContactsRepository make(Executor network,Executor disk) {
        // Delegate chỉ thuộc fixture JVM; giữ nguyên các ca main trễ và InstantTaskExecutorRule.
        return new RealContactsRepository(contacts,users,api,network,disk,session,Runnable::run,
                ArchTaskExecutor.getInstance()::executeOnMainThread);
    }
    @Before public void setup() throws Exception {
        contacts=mock(ContactDao.class); users=mock(UserDao.class); api=mock(ContactApi.class); session=new Session();
        when(contacts.getFriends()).thenReturn(friends);
        when(contacts.getBlockedUsers()).thenReturn(new MutableLiveData<>(List.of()));
        when(contacts.getPendingRequestsWithUsers()).thenReturn(new MutableLiveData<>(List.of()));
        when(contacts.incomingCount(any())).thenReturn(new MutableLiveData<>(0));
        when(users.getUserByPhone(any())).thenReturn(new MutableLiveData<>());
        snapshot(List.of(),List.of(),List.of()); repo=make(Runnable::run,Runnable::run);
    }
    private void snapshot(List<Contact> f,List<Contact> b,List<Request> r) throws Exception {
        when(api.friends()).thenReturn(success(f)); when(api.blocked()).thenReturn(success(b)); when(api.requests()).thenReturn(success(r));
    }
    private void offlineRefresh() throws Exception { when(api.friends()).thenReturn(failure(new IOException("offline"))); }
    private static <T> void observe(LiveData<T> live) { live.observeForever(value -> {}); }

    // Refresh và lỗi dữ liệu không được phá cache đang có.
    @Test public void signedOutDoesNotCallApi() {
        session.change(null,null); assertEquals("UNAUTHENTICATED",repo.refresh().getValue().errorCode); verifyNoInteractions(api);
    }
    @Test public void fullSnapshotMapsFriendsBlockedAndPending() throws Exception {
        snapshot(List.of(contact("B",false)),List.of(contact("C",true)),List.of(request("PENDING")));
        assertTrue(repo.refresh().getValue().isSuccess());
        verify(contacts).clearContacts(); verify(contacts).clearRequests();
        verify(contacts).insert(argThat(c -> "C".equals(c.getContactUserId())&&c.getBlocked()));
        verify(contacts).insertRequest(argThat(r -> "r1".equals(r.getId())&&r.getCreatedAt()>0));
        verify(users,atLeastOnce()).upsertPreservingPrivacy(argThat((com.utechat.data.local.entity.UserEntity u) -> "B".equals(u.getId())));
    }
    @Test public void partialRefreshFailurePreservesEntireCache() throws Exception {
        when(api.blocked()).thenReturn(failure(new IOException("offline")));
        assertEquals("NETWORK",repo.refresh().getValue().errorCode); verify(contacts,never()).clearContacts();
    }
    @Test public void refreshUsesHttpFallbackRatherThanNetworkForHtml() throws Exception {
        when(api.friends()).thenReturn(http(503,"<html>fixture</html>"));
        assertEquals("HTTP_503",repo.refresh().getValue().errorCode); verify(contacts,never()).clearContacts();
    }
    @Test public void duplicateAcrossFriendAndBlockedSnapshotsIsRejected() throws Exception {
        snapshot(List.of(contact("B",false)),List.of(contact("B",true)),List.of());
        assertEquals("INVALID_RESPONSE",repo.refresh().getValue().errorCode); verify(contacts,never()).clearContacts();
    }
    @Test public void missingBodyIsInvalidResponseAndKeepsCache() throws Exception {
        when(api.requests()).thenReturn(success(null));
        assertEquals("INVALID_RESPONSE",repo.refresh().getValue().errorCode); verify(contacts,never()).clearContacts();
    }
    @Test public void unrelatedRequestIsRejectedBeforeTransaction() throws Exception {
        Request r=request("PENDING"); r.requester=user("C"); snapshot(List.of(),List.of(),List.of(r));
        assertEquals("INVALID_RESPONSE",repo.refresh().getValue().errorCode); verify(contacts,never()).clearRequests();
    }
    @Test public void invalidTimestampIsRejectedBeforeTransaction() throws Exception {
        Request r=request("PENDING"); r.createdAt="bad"; snapshot(List.of(),List.of(),List.of(r));
        assertEquals("INVALID_RESPONSE",repo.refresh().getValue().errorCode); verify(contacts,never()).clearRequests();
    }
    @Test public void cacheWriteFailureIsNotReportedAsNetwork() {
        doThrow(new IllegalStateException("disk")).when(contacts).clearContacts();
        assertEquals("CACHE_WRITE_FAILED",repo.refresh().getValue().errorCode);
    }

    // Guard ở ba thời điểm: trước NETWORK, ngay trước DISK và lúc phát kết quả trên main.
    @Test public void queuedNetworkForPreviousSessionDoesNothing() {
        Queue net=new Queue(); repo=make(net,Runnable::run); var out=repo.refresh(); session.change("A","A-session-2"); net.drain();
        verifyNoInteractions(api); assertNull(out.getValue());
    }
    @Test public void sameUserNewSessionCannotWriteOldSnapshot() {
        Queue disk=new Queue(); repo=make(Runnable::run,disk); var out=repo.refresh(); session.change("A","A-session-2"); disk.drain();
        verify(contacts,never()).clearContacts(); assertNull(out.getValue());
    }
    @Test public void oldNetworkFailureDoesNotPublishIntoNewSession() throws Exception {
        Call<List<Contact>> old=failure(new IOException("offline"));
        doAnswer(invocation -> {session.change("B","B-session");throw new IOException("offline");}).when(old).execute();
        when(api.friends()).thenReturn(old);
        assertNull(repo.refresh().getValue()); // Không nhận dữ liệu hoặc lỗi của phiên A.
    }
    @Test public void sessionChangeMidSnapshotStopsRemainingApiCalls() throws Exception {
        Call<List<Contact>> old=success(List.of());
        when(old.execute()).thenAnswer(invocation -> {session.change("B","B-session");return Response.success(List.of());});
        when(api.friends()).thenReturn(old); assertNull(repo.refresh().getValue());
        verify(api,never()).blocked(); verify(api,never()).requests();
    }
    @Test public void mainQueueRechecksSessionBeforeDeliveringLookup() throws Exception {
        when(api.lookup(any())).thenReturn(success(lookup("B")));
        Queue main=new Queue();
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            public void executeOnDiskIO(Runnable r) { r.run(); }
            public void postToMainThread(Runnable r) { main.execute(r); }
            public boolean isMainThread() { return false; }
        });
        try {
            var out=repo.lookup("+84901111111"); session.key="A-session-2"; main.drain(); assertNull(out.getValue());
        } finally { ArchTaskExecutor.getInstance().setDelegate(null); }
    }

    // LiveData cache không tiếp tục đưa hồ sơ cũ ra màn hình sau logout/đổi tài khoản.
    @Test public void otherOwnerCannotReadFriendsOrBlockedUsers() {
        var f=repo.getFriends("B"); var b=repo.getBlockedUsers("B"); observe(f); observe(b);
        assertTrue(f.getValue().isEmpty()); assertTrue(b.getValue().isEmpty());
    }
    @Test public void logoutEmptiesPreviouslyObservedRoomStream() {
        UserEntity u=new UserEntity();u.setId("B"); friends.setValue(List.of(u));
        var live=repo.getFriends("A");observe(live);assertEquals(1,live.getValue().size());
        session.change(null,null);assertTrue(live.getValue().isEmpty());
    }
    @Test public void sameActorReloginCannotReactivateOldRoomStream() {
        var live=repo.getFriends("A");observe(live);session.change("A","A-session-2");
        UserEntity u=new UserEntity();u.setId("C");friends.setValue(List.of(u));assertTrue(live.getValue().isEmpty());
    }
    @Test public void cacheReadWaitsBehindQueuedSessionCleanup() {
        Queue disk=new Queue(); UserEntity old=new UserEntity();old.setId("old");friends.setValue(List.of(old));
        disk.execute(()->friends.setValue(List.of()));repo=make(Runnable::run,disk);
        var live=repo.getFriends("A");observe(live);assertTrue(live.getValue().isEmpty());disk.drain();assertTrue(live.getValue().isEmpty());
    }

    // Lookup không ghi quan hệ, giữ metadata HTTP và chỉ nhận phản hồi mới nhất.
    @Test public void lookupReturnsPendingIdAndDirectionWithoutWritingCache() throws Exception {
        Lookup dto=lookup("B");dto.relationshipStatus="INCOMING_PENDING";dto.hasPendingRequest=true;
        dto.pendingRequestId="r1";dto.pendingRequestDirection="INCOMING";when(api.lookup(any())).thenReturn(success(dto));
        assertEquals("r1",repo.lookup("+84901111111").getValue().data.pendingRequestId);verify(users,never()).insert(any());
    }
    @Test public void lookupRetainsRateLimitMetadata() throws Exception {
        when(api.lookup(any())).thenReturn(http(429,"{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":9}}}"));
        assertEquals(9,repo.lookup("+84901111111").getValue().retryAfterSeconds);
    }
    @Test public void lookupMalformedJsonIsNotOffline() throws Exception {
        when(api.lookup(any())).thenReturn(failure(new com.google.gson.stream.MalformedJsonException("fixture")));
        assertEquals("INVALID_RESPONSE",repo.lookup("+84901111111").getValue().errorCode);
    }
    @Test public void lookupTransportFailurePreservesSession() throws Exception {
        when(api.lookup(any())).thenReturn(failure(new IOException("fixture")));
        assertEquals("NETWORK",repo.lookup("+84901111111").getValue().errorCode);assertEquals("A-session-1",session.key);
    }
    @Test public void lookupRejectsContradictoryPendingMetadata() throws Exception {
        Lookup dto=lookup("B");dto.hasPendingRequest=true;when(api.lookup(any())).thenReturn(success(dto));
        assertEquals("INVALID_RESPONSE",repo.lookup("+84901111111").getValue().errorCode);
        dto.hasPendingRequest=false;dto.isContact=true;
        assertEquals("INVALID_RESPONSE",repo.lookup("+84901111111").getValue().errorCode);
    }
    @Test public void olderLookupCannotOverrideNewerResult() throws Exception {
        AtomicReference<LiveData<ContactsResult<Lookup>>> newer=new AtomicReference<>();
        Call<Lookup> first=success(lookup("B"));when(api.lookup("first")).thenReturn(first);
        when(api.lookup("second")).thenReturn(success(lookup("C")));
        when(first.execute()).thenAnswer(invocation->{newer.set(repo.lookup("second"));return Response.success(lookup("B"));});
        assertNull(repo.lookup("first").getValue());assertEquals("C",newer.get().getValue().data.id);
    }

    // Mỗi route mutation chỉ gọi một lần; ACK thành công tách riêng lỗi refresh phía sau.
    @Test public void sendSavesAcknowledgedPendingAndReportsRefreshFailure() throws Exception {
        when(api.send(any())).thenReturn(response(Response.success(201,request("PENDING"))));offlineRefresh();
        var result=repo.act("send","B",null).getValue();assertTrue(result.isSuccess());assertTrue(result.refreshRequired);
        ArgumentCaptor<Send> body=ArgumentCaptor.forClass(Send.class);verify(api).send(body.capture());assertEquals("B",body.getValue().addresseeId);
        verify(contacts).insertRequest(argThat(r->"PENDING".equals(r.getStatus())));
    }
    @Test public void duplicateSendRetainsExistingRequestAndDirection() throws Exception {
        when(api.send(any())).thenReturn(http(409,"{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{\"existingRequestId\":\"old\",\"direction\":\"INCOMING\"}}}"));
        var r=repo.act("send","B",null).getValue();assertEquals("old",r.existingRequestId);assertEquals("INCOMING",r.direction);
        verify(contacts,never()).insertRequest(any());verify(api,never()).accept(any());
    }
    @Test public void acceptedHistoricalRequestDoesNotInventFriend() throws Exception {
        Request r=request("ACCEPTED");r.requester=user("B");r.addressee=user("A");when(api.accept("r1")).thenReturn(success(r));offlineRefresh();
        var result=repo.act("accept","r1",null).getValue();assertTrue(result.isSuccess());assertTrue(result.refreshRequired);
        verify(contacts).deleteRequest("r1");verify(contacts,never()).insert(any());
    }
    @Test public void rejectRemovesOnlyAcknowledgedRequest() throws Exception {
        Request r=request("REJECTED");r.requester=user("B");r.addressee=user("A");when(api.reject("r1")).thenReturn(success(r));offlineRefresh();
        assertTrue(repo.act("reject","r1",null).getValue().isSuccess());verify(contacts).deleteRequest("r1");
    }
    @Test public void cancelUses204WithoutInventingContact() throws Exception {
        when(api.cancel("r1")).thenReturn(response(Response.success(204,null)));offlineRefresh();
        assertTrue(repo.act("cancel","r1",null).getValue().isSuccess());verify(contacts).deleteRequest("r1");verify(contacts,never()).insert(any());
    }
    @Test public void blockStoresKnownRowAndRemovesBothPendingDirections() throws Exception {
        when(api.block("B")).thenReturn(response(Response.success(204,null)));offlineRefresh();
        assertTrue(repo.act("block","B",user("B")).getValue().refreshRequired);
        verify(contacts).insert(argThat(c->c.getBlocked()&&"B".equals(c.getContactUserId())));verify(contacts).deleteRequestsFor("B");
    }
    @Test public void unrelatedProfileIsNotCachedWhenBlocking() throws Exception {
        when(api.block("B")).thenReturn(response(Response.success(204,null)));offlineRefresh();repo.act("block","B",user("C"));
        verify(users,never()).upsertPreservingPrivacy(any(com.utechat.data.local.entity.UserEntity.class));
    }
    @Test public void unblockDoesNotGuessFriendshipWhenReloadFails() throws Exception {
        when(api.unblock("B")).thenReturn(response(Response.success(204,null)));offlineRefresh();
        assertTrue(repo.act("unblock","B",null).getValue().isSuccess());verify(contacts).deleteContact("B");verify(contacts,never()).insert(any());
    }
    @Test public void unfriendKeepsBlockedRowsThroughScopedDaoOperation() throws Exception {
        when(api.unfriend("B")).thenReturn(response(Response.success(204,null)));offlineRefresh();repo.act("unfriend","B",null);
        verify(contacts).deleteFriend("B");verify(contacts,never()).deleteContact(any());
    }
    @Test public void lostMutationResponseIsUncertainAndNeverRetried() throws Exception {
        when(api.block("B")).thenReturn(failure(new IOException("lost ACK")));
        var r=repo.act("block","B",null).getValue();assertFalse(r.isSuccess());assertTrue(r.refreshRequired);assertEquals("NETWORK",r.errorCode);
        verify(api,times(1)).block("B");verify(contacts,never()).insert(any());
    }
    @Test public void rejectedMutationDoesNotClaimServerCommit() throws Exception {
        when(api.block("B")).thenReturn(http(403,"{\"error\":{\"code\":\"BLOCKED\"}}"));
        var r=repo.act("block","B",null).getValue();assertEquals("BLOCKED",r.errorCode);assertFalse(r.refreshRequired);verify(contacts,never()).insert(any());
    }
    @Test public void malformedSuccessfulMutationAckIsUncertain() throws Exception {
        when(api.send(any())).thenReturn(response(Response.success(201,null)));
        var r=repo.act("send","B",null).getValue();assertEquals("INVALID_RESPONSE",r.errorCode);assertTrue(r.refreshRequired);
        verify(contacts,never()).insertRequest(any());
    }
    @Test public void successfulMutationWithFailedSnapshotWriteStillRemainsConfirmed() throws Exception {
        when(api.cancel("r1")).thenReturn(response(Response.success(204,null)));doThrow(new IllegalStateException("disk")).when(contacts).clearContacts();
        var r=repo.act("cancel","r1",null).getValue();assertTrue(r.isSuccess());assertTrue(r.refreshRequired);
    }
    @Test public void localPatchFailureCanBeHealedByFullSnapshot() throws Exception {
        when(api.block("B")).thenReturn(response(Response.success(204,null)));
        doThrow(new IllegalStateException("patch")).doNothing().when(contacts).insert(any());
        snapshot(List.of(),List.of(contact("B",true)),List.of());
        var r=repo.act("block","B",null).getValue();assertTrue(r.isSuccess());assertFalse(r.refreshRequired);verify(contacts).clearContacts();
    }
    @Test public void mutationAckFromPreviousSessionCannotPatchOrFetchNewAccount() throws Exception {
        Call<Void> call=response(Response.success(204,null));when(api.block("B")).thenReturn(call);
        when(call.execute()).thenAnswer(invocation->{session.change("C","C-session");return Response.success(204,null);});
        assertNull(repo.act("block","B",null).getValue());verify(contacts,never()).insert(any());verify(api,never()).friends();
    }
    @Test public void oldRefreshCannotOverwriteNewBlock() throws Exception {
        Queue disk=new Queue();repo=make(Runnable::run,disk);
        snapshot(List.of(contact("B",false)),List.of(),List.of());var old=repo.refresh();
        snapshot(List.of(),List.of(contact("B",true)),List.of());when(api.block("B")).thenReturn(response(Response.success(204,null)));
        var mutation=repo.act("block","B",null);disk.drain();
        assertEquals("STALE_RESULT",old.getValue().errorCode);assertTrue(mutation.getValue().isSuccess());
        verify(contacts,never()).insert(argThat(c->!c.getBlocked()));verify(contacts,times(1)).clearContacts();
    }
    @Test public void newerRefreshWinsWhenDiskIsDelayed() {
        Queue disk=new Queue();repo=make(Runnable::run,disk);var old=repo.refresh();var fresh=repo.refresh();disk.drain();
        assertEquals("STALE_RESULT",old.getValue().errorCode);assertTrue(fresh.getValue().isSuccess());verify(contacts,times(1)).clearContacts();
    }
    @Test public void invalidActionDoesNotCallApi() {
        assertEquals("VALIDATION",repo.act("unknown","B",null).getValue().errorCode);verifyNoInteractions(api);
    }
    /** Một ACK đợi main vẫn thành công nhưng không được mô tả cache revision cũ là mới nhất. */
    @Test public void confirmedResultRechecksCacheRevisionOnMain() throws Exception {
        when(api.block("B")).thenReturn(response(Response.success(204,null)));Queue main=new Queue();boolean[] onMain={false};
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            public void executeOnDiskIO(Runnable r){r.run();}
            public void postToMainThread(Runnable r){main.execute(r);}
            public boolean isMainThread(){return onMain[0];}
        });
        try {
            var action=repo.act("block","B",null);var fresh=repo.refresh();onMain[0]=true;main.drain();
            assertTrue(action.getValue().isSuccess());assertTrue(action.getValue().refreshRequired);
            assertTrue(fresh.getValue().isSuccess());
        } finally { ArchTaskExecutor.getInstance().setDelegate(null); }
    }
}
