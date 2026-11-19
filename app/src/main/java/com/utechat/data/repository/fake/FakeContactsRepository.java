package com.utechat.data.repository.fake;

import androidx.lifecycle.*;
import com.utechat.data.local.dao.ContactRequestWithUser;
import com.utechat.data.local.entity.*;
import com.utechat.data.remote.model.ContactDtos.*;
import com.utechat.data.repository.*;
import java.time.Instant;
import java.util.*;

/** Backend giả trong bộ nhớ để kiểm thử giao diện; quan hệ chặn và bạn bè được lưu độc lập. */
public class FakeContactsRepository implements ContactsRepository {
    private final Map<String,UserEntity> users=new LinkedHashMap<>();
    private final Set<String> friends=new HashSet<>(),blocks=new HashSet<>();
    private final Map<String,Request> requests=new LinkedHashMap<>();
    private final MutableLiveData<Integer> changes=new MutableLiveData<>(0);
    private final Map<String,MutableLiveData<List<UserEntity>>> lists=new HashMap<>();
    private final CurrentUserIdProvider provider;
    private String observedSession;
    public FakeContactsRepository() { this(null); }
    public FakeContactsRepository(CurrentUserIdProvider provider) {
        this.provider=provider;
        observedSession=sessionKey();
        for(UserEntity u:DemoData.users()) users.put(u.getId(),u);
        for(String a:List.of(DemoData.ALICE,DemoData.BOB,DemoData.CHARLIE))
            for(String b:List.of(DemoData.ALICE,DemoData.BOB,DemoData.CHARLIE)) if(!a.equals(b)) friends.add(pair(a,b));
        blocks.add(pair(DemoData.ALICE,DemoData.EVE));
        createRequest(DemoData.BOB,DemoData.DAVID);
    }
    private static String pair(String a,String b) { return a+":"+b; }
    // Các phương thức mở rộng vẫn dùng trực tiếp được trước khi Repository 12–13 mở rộng interface chung.
    public String currentUserId() { return provider==null?DemoData.ALICE:provider.getCurrentUserId(); }
    public String sessionKey() { return provider==null?DemoData.ALICE:provider.sessionKey(); }
    /** Chỉ dọn cache phía client; dữ liệu mô phỏng server vẫn tồn tại qua các lần đăng nhập. */
    public void onSessionChanged() {
        String key=sessionKey();
        if(Objects.equals(key,observedSession)) return;
        observedSession=key;
        lists.values().forEach(live->live.setValue(Collections.emptyList()));
        lists.clear();
        changes.setValue(changes.getValue()+1);
    }
    private boolean owns(String owner) { return provider==null||Objects.equals(owner,currentUserId())&&owner!=null; }
    private boolean blocked(String a,String b) { return blocks.contains(pair(a,b))||blocks.contains(pair(b,a)); }
    private User dto(String id) { UserEntity u=users.get(id); User v=new User(); v.id=id; v.displayName=u.getDisplayName(); v.avatarUrl=u.getAvatarUrl(); return v; }
    private void createRequest(String a,String b) {
        Request r=new Request(); r.id=UUID.randomUUID().toString(); r.requester=dto(a); r.addressee=dto(b);
        r.status="PENDING"; r.createdAt=Instant.now().toString(); requests.put(r.id,r);
    }
    private Request pending(String a,String b) {
        for(Request r:requests.values()) if("PENDING".equals(r.status)&&
                ((r.requester.id.equals(a)&&r.addressee.id.equals(b))||(r.requester.id.equals(b)&&r.addressee.id.equals(a)))) return r;
        return null;
    }
    private List<UserEntity> list(String owner,boolean blocked) {
        List<UserEntity> out=new ArrayList<>();
        if(!owns(owner)) return out;
        for(UserEntity u:users.values()) if(blocked?blocks.contains(pair(owner,u.getId())):
                friends.contains(pair(owner,u.getId()))&&!blocks.contains(pair(owner,u.getId()))) {
            // Block-only không trả phone như DTO backend; không sửa hồ sơ giả dùng chung cho lookup.
            if(blocked&&!friends.contains(pair(owner,u.getId()))) {
                UserEntity minimal=new UserEntity(); minimal.setId(u.getId()); minimal.setDisplayName(u.getDisplayName());
                minimal.setAvatarUrl(u.getAvatarUrl()); minimal.setAllowMessagesFromNonContacts(u.getAllowMessagesFromNonContacts());
                out.add(minimal);
            } else out.add(u);
        }
        return out;
    }
    @Override public LiveData<List<UserEntity>> getFriends(String owner) { onSessionChanged(); return lists.computeIfAbsent("F"+owner,k->new MutableLiveData<>(list(owner,false))); }
    @Override public LiveData<List<UserEntity>> getBlockedUsers(String owner) { onSessionChanged(); return lists.computeIfAbsent("B"+owner,k->new MutableLiveData<>(list(owner,true))); }
    /** Giữ getValue hoạt động như fixture cũ, đồng thời phát thay đổi cho màn đang quan sát. */
    private void publish() {
        lists.forEach((key,live)->live.setValue(list(key.substring(1),key.startsWith("B"))));
        changes.setValue(changes.getValue()+1);
    }
    @Override public LiveData<UserEntity> searchByPhone(String phone) {
        if(sessionKey()==null) return new MutableLiveData<>(null);
        for(UserEntity u:users.values()) if(phone.equals(u.getPhone())) return new MutableLiveData<>(u);
        return new MutableLiveData<>(null);
    }
    public LiveData<List<ContactRequestWithUser>> getRequests() {
        String key=sessionKey();
        return Transformations.map(changes,x->{
            List<ContactRequestWithUser> out=new ArrayList<>(); String owner=currentUserId();
            if(key==null||!key.equals(sessionKey())) return out;
            for(Request r:requests.values()) if("PENDING".equals(r.status)&&(r.requester.id.equals(owner)||r.addressee.id.equals(owner))) {
                ContactRequestWithUser v=new ContactRequestWithUser(); v.request=new ContactRequestEntity();
                v.request.setId(r.id); v.request.setRequesterId(r.requester.id); v.request.setAddresseeId(r.addressee.id);
                v.request.setStatus(r.status); v.request.setCreatedAt(Instant.parse(r.createdAt).toEpochMilli());
                v.requesterName=r.requester.displayName; v.addresseeName=r.addressee.displayName; out.add(v);
            }
            return out;
        });
    }
    public LiveData<Integer> incomingCount(String owner) {
        String key=sessionKey();
        return Transformations.map(changes,x->{ int count=0; if(key==null||!key.equals(sessionKey())||!owns(owner)) return count; for(Request r:requests.values()) if("PENDING".equals(r.status)&&r.addressee.id.equals(owner)) count++; return count; });
    }
    public LiveData<ContactsResult<Boolean>> refresh() { onSessionChanged(); publish(); return new MutableLiveData<>(sessionKey()==null?ContactsResult.error("UNAUTHENTICATED"):ContactsResult.ok(true)); }
    public LiveData<ContactsResult<Lookup>> lookup(String phone) {
        if(sessionKey()==null) return new MutableLiveData<>(ContactsResult.error("UNAUTHENTICATED"));
        String owner=currentUserId(); UserEntity found=null;
        for(UserEntity u:users.values()) if(phone.equals(u.getPhone())) found=u;
        if(found==null||blocked(owner,found.getId())) return new MutableLiveData<>(ContactsResult.error("NOT_FOUND"));
        if(found.getId().equals(owner)) return new MutableLiveData<>(ContactsResult.error("SELF"));
        Lookup r=new Lookup(); r.id=found.getId(); r.displayName=found.getDisplayName(); r.avatarUrl=found.getAvatarUrl();
        Request p=pending(owner,r.id); r.isContact=friends.contains(pair(owner,r.id)); r.hasPendingRequest=p!=null;
        if(p!=null) { r.pendingRequestId=p.id; r.pendingRequestDirection=p.requester.id.equals(owner)?"OUTGOING":"INCOMING"; }
        r.relationshipStatus=r.isContact?"FRIEND":p==null?"STRANGER":r.pendingRequestDirection+"_PENDING";
        return new MutableLiveData<>(ContactsResult.ok(r));
    }
    public LiveData<ContactsResult<Boolean>> act(String action,String id,User profile) {
        if(sessionKey()==null) return new MutableLiveData<>(ContactsResult.error("UNAUTHENTICATED"));
        String owner=currentUserId(); ContactsResult<Boolean> result=ContactsResult.ok(true);
        if(List.of("accept","reject","cancel").contains(action)) {
            Request r=requests.get(id);
            if(r==null) result=ContactsResult.error("NOT_FOUND");
            else if(!("cancel".equals(action)?r.requester.id:r.addressee.id).equals(owner)) result=ContactsResult.error("FORBIDDEN");
            else {
                String next="accept".equals(action)?"ACCEPTED":"reject".equals(action)?"REJECTED":"CANCELED";
                if("accept".equals(action)&&blocked(r.requester.id,r.addressee.id)) result=ContactsResult.error("BLOCKED");
                else if(!r.status.equals(next)&&!"PENDING".equals(r.status)) result=ContactsResult.error("INVALID_STATE");
                else if(!r.status.equals(next)) {
                    r.status=next;
                    if("ACCEPTED".equals(next)) { friends.add(pair(r.requester.id,r.addressee.id)); friends.add(pair(r.addressee.id,r.requester.id)); }
                }
            }
        } else if(!users.containsKey(id)) result=ContactsResult.error("NOT_FOUND");
        else if(id.equals(owner)) result=ContactsResult.error("SELF");
        else switch(action) {
            case "send":
                if(blocked(owner,id)) result=ContactsResult.error("BLOCKED");
                else if(friends.contains(pair(owner,id))) result=ContactsResult.error("ALREADY_FRIENDS");
                else { Request p=pending(owner,id); if(p==null) createRequest(owner,id); else {
                    result=ContactsResult.error("REQUEST_ALREADY_PENDING"); result.existingRequestId=p.id;
                    result.direction=p.requester.id.equals(owner)?"OUTGOING":"INCOMING";
                } } break;
            case "block": blocks.add(pair(owner,id)); Request p=pending(owner,id); if(p!=null) p.status="CANCELED"; break;
            case "unblock": blocks.remove(pair(owner,id)); break;
            case "unfriend": friends.remove(pair(owner,id)); friends.remove(pair(id,owner)); break;
            default: result=ContactsResult.error("VALIDATION");
        }
        publish(); return new MutableLiveData<>(result);
    }
}
