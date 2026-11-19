package com.utechat.ui.contacts;

import static androidx.lifecycle.SavedStateHandleSupport.createSavedStateHandle;
import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;
import android.os.SystemClock;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.*;
import androidx.lifecycle.Observer;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.R;
import com.utechat.data.local.dao.ContactRequestWithUser;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.model.ContactDtos.*;
import com.utechat.data.repository.*;
import com.utechat.di.Injection;
import java.util.*;
import java.util.function.LongSupplier;

/** Mục 15: trạng thái theo graph và phiên; SavedStateHandle giữ dữ liệu nhập qua recreate. */
public class ContactsViewModel extends ViewModel {
    public static final ViewModelProvider.Factory FACTORY=ViewModelProvider.Factory.from(
        new ViewModelInitializer<>(ContactsViewModel.class,e->new ContactsViewModel(createSavedStateHandle(e),Injection.provideContactsRepository(e.get(APPLICATION_KEY)))));
    public static ContactsViewModel of(Fragment f) {
        return new ViewModelProvider(NavHostFragment.findNavController(f).getViewModelStoreOwner(R.id.nav_contacts),FACTORY).get(ContactsViewModel.class);
    }
    public final SavedStateHandle state;
    private final ContactsRepository repo;
    private final String session,owner;
    private final LongSupplier clock;
    private boolean invalidated,requested,cleared;
    private long lookupVersion;
    private LiveData<?> pending;
    private Observer<?> pendingObserver;
    private final List<Runnable> resets=new ArrayList<>();
    private final List<Runnable> detachers=new ArrayList<>();
    private final LiveData<String> sessions,phoneState;
    private final Observer<String> sessionObserver,phoneObserver;
    private String lastPhone;
    public final LiveData<List<UserEntity>> friends,blocked;
    public final LiveData<List<ContactRequestWithUser>> requests;
    public final LiveData<Integer> count;
    public final MutableLiveData<Lookup> lookup=new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy=new MutableLiveData<>(false),sessionActive=new MutableLiveData<>(true),needsRefresh=new MutableLiveData<>(false);
    public final MutableLiveData<String> operation=new MutableLiveData<>();
    public final MutableLiveData<ContactsResult<?>> notice=new MutableLiveData<>(),error=new MutableLiveData<>();
    public final MutableLiveData<ContactsResult<?>> listError=new MutableLiveData<>();

    public ContactsViewModel(SavedStateHandle state,ContactsRepository repo) { this(state,repo,SystemClock::elapsedRealtime); }
    /** Đồng hồ monotonic được truyền vào test để không phải sleep khi kiểm 429. */
    public ContactsViewModel(SavedStateHandle state,ContactsRepository repo,LongSupplier clock) {
        this.state=state;this.repo=repo;this.clock=clock;session=repo.sessionKey();owner=repo.currentUserId();
        String saved=state.get("contacts_session");
        if(saved!=null&&!Objects.equals(saved,session)) clearInputs();
        state.set("contacts_session",session);
        friends=scope(repo.getFriends(owner),List.of());blocked=scope(repo.getBlockedUsers(owner),List.of());
        requests=scope(repo.getRequests(),List.of());count=scope(repo.incomingCount(owner),0);
        sessions=repo.sessionChanges();
        // LiveData có thể chưa phát khóa khi khôi phục tiến trình; đối chiếu khóa thật từ Repository.
        sessionObserver=key->{if(!current()) invalidate();};
        phoneState=state.getLiveData("phone","");
        lastPhone=phone();
        phoneObserver=value->{
            if(Objects.equals(lastPhone,value)) return;
            lastPhone=value;++lookupVersion;lookup.setValue(null);
            if("lookup".equals(operation.getValue())) { cancelPending();finish(); }
        };
        sessions.observeForever(sessionObserver);phoneState.observeForever(phoneObserver);
        if(session==null) invalidate();
    }
    private <T> LiveData<T> scope(LiveData<T> source,T empty) {
        MediatorLiveData<T> out=new MediatorLiveData<>(empty);
        out.addSource(source,value->out.setValue(current()&&value!=null?value:empty));
        resets.add(()->out.setValue(empty));detachers.add(()->out.removeSource(source));return out;
    }
    public String owner() { return owner; }
    public String phone() { String s=state.get("phone");return s==null?"":s; }
    public String filter() { String s=state.get("filter");return s==null?"":s; }
    public int tab() { Integer i=state.get("tab");return i==null?0:i; }
    private boolean current() { return !invalidated&&!cleared&&session!=null&&session.equals(repo.sessionKey()); }
    public boolean canAct() { return current()&&!Boolean.TRUE.equals(busy.getValue())&&!Boolean.TRUE.equals(needsRefresh.getValue()); }
    public int retrySeconds() {
        Long until=state.get("retry_until");long ms=until==null?0:until-clock.getAsLong();
        return (int)Math.min(Integer.MAX_VALUE,Math.max(0,(ms+999)/1000));
    }
    public List<String> availableActions() {
        if(!canAct()||lookup.getValue()==null) return List.of();
        try { return RelationshipState.valueOf(lookup.getValue().relationshipStatus).actions(); }
        catch(IllegalArgumentException|NullPointerException invalid) { return List.of(); }
    }
    private void clearInputs() { state.set("phone","");state.set("filter","");state.set("tab",0);state.set("retry_until",0L); }
    private void invalidate() {
        if(invalidated) return;
        invalidated=true;cancelPending();++lookupVersion;clearInputs();lookup.setValue(null);notice.setValue(null);error.setValue(null);listError.setValue(null);
        needsRefresh.setValue(false);sessionActive.setValue(false);finish();resets.forEach(Runnable::run);
    }
    @SuppressWarnings("unchecked") private void cancelPending() {
        if(pending!=null) ((LiveData<Object>)pending).removeObserver((Observer<Object>)pendingObserver);
        pending=null;pendingObserver=null;
    }
    private void begin(String action) { operation.setValue(action);busy.setValue(true); }
    private void finish() { operation.setValue(null);busy.setValue(false); }
    /** Đăng ký một observer cho một tác vụ, tháo cả khi callback đồng bộ, đổi phiên hoặc onCleared. */
    private <T> void once(LiveData<T> source,java.util.function.Consumer<T> consumer) {
        Observer<T> observer=new Observer<>() {
            @Override public void onChanged(T value) {
                if(value==null) return;
                cancelPending();
                if(current()) consumer.accept(value);else invalidate();
            }
        };
        pending=source;pendingObserver=observer;source.observeForever(observer);
    }
    /** Fragment recreate chỉ quan sát lại, không tự phát một refresh mới nếu ViewModel đã yêu cầu tải. */
    public void ensureLoaded() { if(!requested) { requested=true;refresh(); } }
    public ContactsResult<?> consumeNotice() {
        ContactsResult<?> value=notice.getValue();notice.setValue(null);return value;
    }
    private void failure(ContactsResult<?> result) {
        if("RATE_LIMITED".equals(result.errorCode))
            state.set("retry_until",clock.getAsLong()+Math.max(0,(long)result.retryAfterSeconds)*1000);
        error.setValue(result);notice.setValue(result);
    }
    public void refresh() {
        if(!current()||Boolean.TRUE.equals(busy.getValue())) return;
        begin("refresh");error.setValue(null);listError.setValue(null);
        once(repo.refresh(),r->{
            finish();
            if(r.isSuccess()) { needsRefresh.setValue(r.refreshRequired);error.setValue(null); }
            else { listError.setValue(r);failure(r); }
        });
    }
    public void search() {
        if(!current()||Boolean.TRUE.equals(busy.getValue())) return;
        String phone=ContactsValidator.normalize(phone());
        if(phone==null) { lookup.setValue(null);failure(ContactsResult.error("PHONE_INVALID"));return; }
        int remaining=retrySeconds();
        if(remaining>0) {
            ContactsResult<Lookup> limited=ContactsResult.error("RATE_LIMITED");limited.retryAfterSeconds=remaining;
            notice.setValue(limited);return;
        }
        long version=++lookupVersion;begin("lookup");error.setValue(null);
        once(repo.lookup(phone),r->{
            finish();
            if(version!=lookupVersion) return;
            lookup.setValue(r.isSuccess()?r.data:null);
            if(!r.isSuccess()) failure(r);
        });
    }
    /** 409 chỉ cập nhật pending khi ID và chiều đều hợp lệ; không tự accept lời mời ngược chiều. */
    private void pendingConflict(ContactsResult<?> result) {
        Lookup old=lookup.getValue();String direction=result.direction;
        if(old==null||result.existingRequestId==null||result.existingRequestId.isBlank()
                ||!("INCOMING".equals(direction)||"OUTGOING".equals(direction))) {
            lookup.setValue(null);needsRefresh.setValue(true);return;
        }
        Lookup updated=new Lookup();updated.id=old.id;updated.displayName=old.displayName;updated.avatarUrl=old.avatarUrl;
        updated.hasPendingRequest=true;updated.isContact=false;updated.pendingRequestId=result.existingRequestId;
        updated.pendingRequestDirection=direction;updated.relationshipStatus=direction+"_PENDING";lookup.setValue(updated);
    }
    public void act(String action,String id,User profile) {
        if(!canAct()) return;
        if(!Set.of("send","accept","reject","cancel","unfriend","block","unblock").contains(action==null?"":action)) {
            failure(ContactsResult.error("VALIDATION"));return;
        }
        begin(action);++lookupVersion;error.setValue(null);
        once(repo.act(action,id,profile),r->{
            finish();needsRefresh.setValue(r.refreshRequired);
            if("REQUEST_ALREADY_PENDING".equals(r.errorCode)) pendingConflict(r);
            else if(r.isSuccess()||r.refreshRequired) lookup.setValue(null); // Không suy bạn bè từ ACK hay kết quả ghi chưa rõ.
            if(Boolean.TRUE.equals(needsRefresh.getValue())) listError.setValue(r);
            else if(r.isSuccess()) listError.setValue(null);
            if(!r.isSuccess()) failure(r);else notice.setValue(r);
        });
    }
    @Override protected void onCleared() {
        cleared=true;cancelPending();sessions.removeObserver(sessionObserver);phoneState.removeObserver(phoneObserver);
        resets.forEach(Runnable::run);detachers.forEach(Runnable::run);
    }
}
