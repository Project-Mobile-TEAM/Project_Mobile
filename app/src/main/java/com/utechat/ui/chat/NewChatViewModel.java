package com.utechat.ui.chat;

import static androidx.lifecycle.SavedStateHandleSupport.createSavedStateHandle;
import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import com.utechat.R;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.model.ContactDtos;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.di.Injection;
import com.utechat.ui.auth.AuthValidator;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tin nhắn mới (D-01): chọn bạn bè, tra SĐT người lạ qua ContactsRepository của Phase 4 (không viết API tra cứu thứ hai),
 * mở/tạo chat 1-1, và gửi lời mời kết bạn khi người kia chỉ nhận tin từ bạn bè (CONTACT_REQUIRED).
 */
public class NewChatViewModel extends ViewModel {
    private static final String KEY_INPUT = "input";

    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(NewChatViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new NewChatViewModel(createSavedStateHandle(extras),
                        Injection.provideChatRepository(app), Injection.provideContactsRepository(app));
            }));

    /** Thẻ kết quả tra SĐT: người tìm được, hoặc mã lỗi; contactRequired bật khi server đòi kết bạn trước. */
    public static final class LookupState {
        @Nullable public final ContactDtos.Lookup found;
        @Nullable public final String errorCode;
        public final boolean contactRequired, requestSent;
        LookupState(@Nullable ContactDtos.Lookup found, @Nullable String errorCode, boolean contactRequired, boolean requestSent) {
            this.found = found; this.errorCode = errorCode; this.contactRequired = contactRequired; this.requestSent = requestSent;
        }
    }

    private final SavedStateHandle state;
    private final ChatRepository chat;
    private final ContactsRepository contacts;
    private final LiveData<List<UserEntity>> friends;
    public final MutableLiveData<LookupState> lookup = new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    /** Sự kiện một lần: id hội thoại cần mở, hoặc chuỗi thông báo; Fragment đọc xong thì gọi consume. */
    public final MutableLiveData<String> openConversation = new MutableLiveData<>();
    public final MutableLiveData<Integer> notice = new MutableLiveData<>();

    public NewChatViewModel(SavedStateHandle state, ChatRepository chat, ContactsRepository contacts) {
        this.state = state;
        this.chat = chat;
        this.contacts = contacts;
        String me = contacts.currentUserId();
        friends = me == null ? new MutableLiveData<>(Collections.emptyList()) : contacts.getFriends(me);
    }

    public LiveData<List<UserEntity>> getFriends() { return friends; }
    public LiveData<String> getInput() { return state.getLiveData(KEY_INPUT, ""); }

    public void setInput(String value) {
        if (value.equals(state.get(KEY_INPUT))) return;
        state.set(KEY_INPUT, value);
        lookup.setValue(null); // Đổi số thì thẻ kết quả cũ không còn đúng.
    }

    /** SĐT hợp lệ (dạng E.164) nếu ô nhập là số điện thoại; null nếu đang gõ tên. */
    @Nullable public static String phoneOf(@Nullable String input) {
        if (input == null || !input.trim().matches("[+\\d][\\d\\s.\\-]*")) return null;
        return AuthValidator.toE164(input);
    }

    public void lookupPhone() {
        String e164 = phoneOf(getInput().getValue());
        if (e164 == null || Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        once(contacts.lookup(e164), r -> {
            busy.setValue(false);
            lookup.setValue(r.isSuccess() ? new LookupState(r.data, null, false, false) : new LookupState(null, r.errorCode, false, false));
        });
    }

    /** Mở chat 1-1; server trả hội thoại cũ nếu đã có nên mở lại cùng người không tạo phòng mới. */
    public void openDirect(String userId, @Nullable ContactDtos.User profile) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        once(chat.openOrCreateDirect(userId), r -> {
            busy.setValue(false);
            if (r.isSuccess() && r.data != null) { openConversation.setValue(r.data); return; }
            if ("CONTACT_REQUIRED".equals(r.errorCode)) {
                lookup.setValue(new LookupState(asLookup(userId, profile), null, true, false));
                return;
            }
            notice.setValue(ChatErrors.message(r.errorCode));
        });
    }

    public void sendFriendRequest(String userId, @Nullable ContactDtos.User profile) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        once(contacts.act("send", userId, profile), r -> {
            busy.setValue(false);
            // Lời mời đã chờ sẵn cũng coi như đã gửi: người dùng không cần làm gì thêm.
            boolean sent = r.isSuccess() || "REQUEST_ALREADY_PENDING".equals(r.errorCode);
            if (sent) {
                LookupState cur = lookup.getValue();
                lookup.setValue(new LookupState(cur == null ? asLookup(userId, profile) : cur.found, null, true, true));
                notice.setValue(R.string.chat_friend_request_sent);
            } else {
                notice.setValue(ChatErrors.message(r.errorCode));
            }
        });
    }

    public void consumeOpen() { openConversation.setValue(null); }
    public void consumeNotice() { notice.setValue(null); }

    private static ContactDtos.Lookup asLookup(String userId, @Nullable ContactDtos.User profile) {
        if (profile instanceof ContactDtos.Lookup) return (ContactDtos.Lookup) profile;
        ContactDtos.Lookup l = new ContactDtos.Lookup();
        l.id = userId;
        if (profile != null) { l.displayName = profile.displayName; l.avatarUrl = profile.avatarUrl; }
        return l;
    }

    /** Repository trả LiveData phát một lần; nghe đúng một giá trị rồi gỡ observer. */
    private static <T> void once(LiveData<T> source, Consumer<T> handler) {
        source.observeForever(new Observer<T>() {
            @Override public void onChanged(T value) {
                if (value == null) return;
                source.removeObserver(this);
                handler.accept(value);
            }
        });
    }
}
