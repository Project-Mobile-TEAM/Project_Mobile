package com.utechat.data.repository.real;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.lifecycle.*;
import com.utechat.data.local.dao.*;
import com.utechat.data.local.entity.*;
import com.utechat.data.remote.api.ContactApi;
import com.utechat.data.remote.model.ContactDtos.*;
import com.utechat.data.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import retrofit2.*;

/** Mục 12–13: NETWORK và DISK đều FIFO; DISK dùng chung với dọn phiên ở Injection. */
public class RealContactsRepository implements ContactsRepository {
    private final ContactDao contacts;
    private final UserDao users;
    private final ContactApi api;
    private final Executor network,disk;
    private final CurrentUserIdProvider session;
    private final Consumer<Runnable> transaction;
    // Đổi khi đọc mới hoặc bắt đầu/kết thúc ghi; snapshot trước thao tác mới không được ghi đè cache.
    private final AtomicLong epoch=new AtomicLong(), lookupVersion=new AtomicLong();

    public RealContactsRepository(ContactDao contacts,UserDao users,ContactApi api,Executor network,Executor disk,
            CurrentUserIdProvider session,Consumer<Runnable> transaction) {
        this.contacts=Objects.requireNonNull(contacts); this.users=Objects.requireNonNull(users);
        this.api=Objects.requireNonNull(api); this.network=Objects.requireNonNull(network);
        this.disk=Objects.requireNonNull(disk); this.session=Objects.requireNonNull(session);
        this.transaction=Objects.requireNonNull(transaction);
    }
    @Override public String sessionKey() { return session.sessionKey(); }
    @Override public String currentUserId() { return session.getCurrentUserId(); }

    /** Luồng cache thuộc một lần đăng nhập; đổi phiên thì chỉ phát rỗng, kể cả đăng nhập lại cùng actor. */
    private <T> LiveData<T> scoped(LiveData<T> source,T empty,String owner) {
        String key=sessionKey();
        MediatorLiveData<T> out=new MediatorLiveData<>(empty);
        if(key==null||!Objects.equals(owner,currentUserId())) return out;
        out.addSource(session.sessionChanges(), ignored -> {
            if(!key.equals(sessionKey())) out.setValue(empty);
        });
        // Hàng rào FIFO: đợi tác vụ clear Room của phiên trước trước khi kích hoạt truy vấn.
        disk.execute(() -> ArchTaskExecutor.getInstance().executeOnMainThread(() ->
            session.runIfCurrent(key,() -> out.addSource(source,value -> {
                if(!session.runIfCurrent(key,() -> out.setValue(value==null?empty:value))) out.setValue(empty);
            }))
        ));
        return out;
    }
    @Override public LiveData<List<UserEntity>> getFriends(String owner) {
        return scoped(contacts.getFriends(),Collections.emptyList(),owner);
    }
    @Override public LiveData<List<UserEntity>> getBlockedUsers(String owner) {
        return scoped(contacts.getBlockedUsers(),Collections.emptyList(),owner);
    }
    @Override public LiveData<UserEntity> searchByPhone(String phone) {
        return scoped(users.getUserByPhone(phone),null,currentUserId());
    }
    @Override public LiveData<List<ContactRequestWithUser>> getRequests() {
        return scoped(contacts.getPendingRequestsWithUsers(),Collections.emptyList(),currentUserId());
    }
    @Override public LiveData<Integer> incomingCount(String owner) {
        return scoped(contacts.incomingCount(owner),0,owner);
    }

    /** Kiểm lại trên main thay vì postValue dữ liệu cũ rồi mới phát hiện phiên đã đổi. */
    private <T> void publish(String key,MutableLiveData<ContactsResult<T>> out,ContactsResult<T> value) {
        publish(key,out,value,() -> true);
    }
    private <T> void publish(String key,MutableLiveData<ContactsResult<T>> out,ContactsResult<T> value,BooleanSupplier fresh) {
        ArchTaskExecutor.getInstance().executeOnMainThread(() ->
            session.runIfCurrent(key,() -> { if(fresh.getAsBoolean()) out.setValue(value); })
        );
    }
    private boolean current(String key) { return key!=null&&key.equals(sessionKey()); }
    private static void require(boolean condition) {
        if(!condition) throw new IllegalArgumentException("Phản hồi danh bạ không hợp lệ");
    }
    private static boolean text(String value) { return value!=null&&!value.trim().isEmpty(); }
    private static void user(User value) { require(value!=null&&text(value.id)&&value.displayName!=null); }
    private static void request(Request value,String owner) {
        require(value!=null&&text(value.id));
        user(value.requester); user(value.addressee);
        require(!value.requester.id.equals(value.addressee.id));
        require(owner.equals(value.requester.id)||owner.equals(value.addressee.id));
        require(Set.of("PENDING","ACCEPTED","REJECTED","CANCELED").contains(value.status==null?"":value.status));
        Instant.parse(value.createdAt);
    }
    private static final class RemoteFailure extends Exception {
        final ContactsResult<Boolean> result;
        RemoteFailure(Response<?> response) { result=ContactErrorMapper.fromHttp(response); }
    }
    private static <T> T body(Call<T> call) throws Exception {
        Response<T> response=call.execute();
        if(!response.isSuccessful()) throw new RemoteFailure(response);
        require(response.code()==200&&response.body()!=null);
        return response.body();
    }
    private record Snapshot(List<Contact> friends,List<Contact> blocked,List<Request> requests) { }

    /** Tải và kiểm đủ ba danh sách trước khi mở transaction; lỗi một phần giữ toàn bộ cache cũ. */
    private void checkCurrent(String key) {
        if(!current(key)) throw new java.util.concurrent.CancellationException("Phiên đã thay đổi");
    }
    private Snapshot fetch(String key,String owner) throws Exception {
        checkCurrent(key); List<Contact> friends=body(api.friends());
        checkCurrent(key); List<Contact> blocked=body(api.blocked());
        checkCurrent(key); List<Request> requests=body(api.requests());
        checkCurrent(key);
        Set<String> ids=new HashSet<>(), keys=new HashSet<>();
        for(Contact c:friends) { user(c); require(!c.blocked&&!owner.equals(c.id)&&ids.add(c.id)); }
        for(Contact c:blocked) { user(c); require(c.blocked&&!owner.equals(c.id)&&ids.add(c.id)); }
        for(Request r:requests) { request(r,owner); require("PENDING".equals(r.status)&&keys.add(r.id)); }
        return new Snapshot(friends,blocked,requests);
    }
    private void saveUser(User dto) {
        UserEntity u=new UserEntity(); u.setId(dto.id); u.setDisplayName(dto.displayName);
        u.setAvatarUrl(dto.avatarUrl); u.setPhone(dto instanceof Contact?((Contact)dto).phone:null);
        // DTO không mang quyền này: UserDao giữ quyền đã biết, hồ sơ mới dùng mặc định V1.
        u.setAllowMessagesFromNonContacts(true); users.upsertPreservingPrivacy(u);
    }
    private void saveContact(String id,boolean blocked) {
        ContactEntity c=new ContactEntity(); c.setContactUserId(id); c.setBlocked(blocked); contacts.insert(c);
    }
    private void saveRequest(Request r) {
        saveUser(r.requester); saveUser(r.addressee);
        ContactRequestEntity row=new ContactRequestEntity(); row.setId(r.id); row.setRequesterId(r.requester.id);
        row.setAddresseeId(r.addressee.id); row.setStatus(r.status); row.setCreatedAt(Instant.parse(r.createdAt).toEpochMilli());
        contacts.insertRequest(row);
    }
    private void apply(Snapshot snapshot) {
        transaction.accept(() -> {
            contacts.clearContacts(); contacts.clearRequests();
            for(Contact c:snapshot.friends()) { saveUser(c); saveContact(c.id,false); }
            for(Contact c:snapshot.blocked()) { saveUser(c); saveContact(c.id,true); }
            for(Request r:snapshot.requests()) saveRequest(r);
        });
    }
    @Override public LiveData<ContactsResult<Boolean>> refresh() {
        MutableLiveData<ContactsResult<Boolean>> out=new MutableLiveData<>();
        String key=sessionKey(), owner=currentUserId(); epoch.incrementAndGet();
        if(key==null) { out.setValue(ContactsResult.error("UNAUTHENTICATED")); return out; }
        network.execute(() -> {
            if(!current(key)) return;
            long ticket=epoch.incrementAndGet();
            try {
                Snapshot snapshot=fetch(key,owner);
                disk.execute(() -> session.runIfCurrent(key,() -> {
                    if(epoch.get()!=ticket) { publish(key,out,ContactsResult.error("STALE_RESULT")); return; }
                    try { apply(snapshot); publish(key,out,ContactsResult.ok(true),() -> epoch.get()==ticket); }
                    catch(RuntimeException failure) { publish(key,out,ContactsResult.error("CACHE_WRITE_FAILED"),() -> epoch.get()==ticket); }
                }));
            } catch(RemoteFailure failure) { publish(key,out,failure.result,() -> epoch.get()==ticket); }
            catch(Exception failure) { publish(key,out,ContactErrorMapper.fromFailure(failure),() -> epoch.get()==ticket); }
        });
        return out;
    }
    @Override public LiveData<ContactsResult<Lookup>> lookup(String phone) {
        MutableLiveData<ContactsResult<Lookup>> out=new MutableLiveData<>();
        String key=sessionKey(); long version=lookupVersion.incrementAndGet();
        if(key==null) { out.setValue(ContactsResult.error("UNAUTHENTICATED")); return out; }
        network.execute(() -> {
            if(!current(key)) return;
            ContactsResult<Lookup> result;
            try {
                Response<Lookup> response=api.lookup(phone).execute();
                if(!current(key)) return;
                if(!response.isSuccessful()) result=ContactErrorMapper.fromHttp(response);
                else {
                    Lookup dto=response.body(); user(dto);
                    require(response.code()==200&&Set.of("STRANGER","FRIEND","BLOCKED","OUTGOING_PENDING","INCOMING_PENDING").contains(dto.relationshipStatus==null?"":dto.relationshipStatus));
                    boolean pending=dto.relationshipStatus.endsWith("_PENDING");
                    require(dto.hasPendingRequest==pending);
                    require(!Objects.equals(dto.id,currentUserId()));
                    if(pending) require(text(dto.pendingRequestId)&&dto.relationshipStatus.equals(dto.pendingRequestDirection+"_PENDING"));
                    else require(dto.pendingRequestId==null&&dto.pendingRequestDirection==null);
                    require(!"FRIEND".equals(dto.relationshipStatus)||dto.isContact);
                    if(pending||"STRANGER".equals(dto.relationshipStatus)) require(!dto.isContact);
                    result=ContactsResult.ok(dto);
                }
            } catch(Exception failure) { result=ContactErrorMapper.fromFailure(failure); }
            publish(key,out,result,() -> lookupVersion.get()==version);
        });
        return out;
    }

    /** Giữ chữ ký act của nháp/UI; chỉ server quyết định trạng thái, không tự accept lời mời ngược chiều. */
    @Override public LiveData<ContactsResult<Boolean>> act(String action,String id,User profile) {
        MutableLiveData<ContactsResult<Boolean>> out=new MutableLiveData<>();
        String key=sessionKey(), owner=currentUserId();
        if(key==null) { out.setValue(ContactsResult.error("UNAUTHENTICATED")); return out; }
        if(!text(id)||!Set.of("send","accept","reject","cancel","block","unblock","unfriend").contains(action==null?"":action)) {
            out.setValue(ContactsResult.error("VALIDATION")); return out;
        }
        epoch.incrementAndGet(); lookupVersion.incrementAndGet();
        network.execute(() -> {
            if(!current(key)) return;
            epoch.incrementAndGet();
            try {
                Call<?> call=switch(action) {
                    case "send" -> api.send(new Send(id));
                    case "accept" -> api.accept(id);
                    case "reject" -> api.reject(id);
                    case "cancel" -> api.cancel(id);
                    case "block" -> api.block(id);
                    case "unblock" -> api.unblock(id);
                    default -> api.unfriend(id);
                };
                Response<?> response=call.execute();
                if(!current(key)) return;
                if(!response.isSuccessful()) { publish(key,out,ContactErrorMapper.fromHttp(response)); return; }
                long ticket=epoch.incrementAndGet();
                Request acknowledged=null;
                if(Set.of("send","accept","reject").contains(action)) {
                    require(response.code()==("send".equals(action)?201:200)&&response.body() instanceof Request);
                    acknowledged=(Request)response.body(); request(acknowledged,owner);
                    String state="send".equals(action)?"PENDING":"accept".equals(action)?"ACCEPTED":"REJECTED";
                    require(state.equals(acknowledged.status));
                    if("send".equals(action)) require(owner.equals(acknowledged.requester.id)&&id.equals(acknowledged.addressee.id));
                    else require(id.equals(acknowledged.id)&&owner.equals(acknowledged.addressee.id));
                } else require(response.code()==204&&response.body()==null);
                Request saved=acknowledged;
                // DISK FIFO giữ thứ tự các mutation đã xác nhận; không để refresh mới làm mất patch chắc chắn.
                disk.execute(() -> session.runIfCurrent(key,() -> {
                    try { transaction.accept(() -> {
                        if("send".equals(action)) saveRequest(saved);
                        else if(Set.of("accept","reject","cancel").contains(action)) contacts.deleteRequest(id);
                        else if("block".equals(action)) {
                            if(profile!=null&&id.equals(profile.id)&&profile.displayName!=null) saveUser(profile);
                            saveContact(id,true); contacts.deleteRequestsFor(id);
                        } else if("unblock".equals(action)) contacts.deleteContact(id);
                        else contacts.deleteFriend(id);
                    }); } catch(RuntimeException ignored) { /* Đối soát đầy đủ bên dưới có thể sửa cache; nếu lỗi thì yêu cầu refresh. */ }
                }));
                try {
                    Snapshot snapshot=fetch(key,owner);
                    disk.execute(() -> session.runIfCurrent(key,() -> {
                        boolean refreshed=false;
                        if(epoch.get()==ticket) try { apply(snapshot); refreshed=true; }
                        catch(RuntimeException ignored) { /* ACK giữ nguyên; cache chưa đối soát được. */ }
                        confirmed(key,out,ticket,refreshed);
                    }));
                } catch(Exception failure) {
                    // Server đã trả ACK hợp lệ; tải lại lỗi không biến mutation thành thất bại để người dùng gửi lại.
                    disk.execute(() -> confirmed(key,out,ticket,false));
                }
            } catch(Exception failure) {
                // Chưa có ACK hợp lệ: có thể server đã commit trước khi mất phản hồi; không tự retry.
                ContactsResult<Boolean> result=ContactErrorMapper.fromFailure(failure); result.refreshRequired=true;
                publish(key,out,result);
            }
        });
        return out;
    }
    /** Revision có thể đổi khi main đang bận: ACK vẫn đúng nhưng không báo cache cũ là đã mới. */
    private void confirmed(String key,MutableLiveData<ContactsResult<Boolean>> out,long ticket,boolean refreshed) {
        ArchTaskExecutor.getInstance().executeOnMainThread(() -> session.runIfCurrent(key,() -> {
            ContactsResult<Boolean> result=ContactsResult.ok(true);
            result.refreshRequired=!refreshed||epoch.get()!=ticket;
            out.setValue(result);
        }));
    }
}
