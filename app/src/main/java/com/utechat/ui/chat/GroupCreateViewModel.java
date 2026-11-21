package com.utechat.ui.chat;

import static androidx.lifecycle.SavedStateHandleSupport.createSavedStateHandle;
import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import com.utechat.R;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.di.Injection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tạo nhóm (D-10): tên 1–50 ký tự, chọn ít nhất 2 bạn bè, tổng tối đa 50 người; tên và lựa chọn sống qua xoay màn hình. */
public class GroupCreateViewModel extends ViewModel {
    static final int MAX_MEMBERS = 50;
    private static final String K_TITLE = "title", K_SELECTED = "selected";

    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(GroupCreateViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new GroupCreateViewModel(createSavedStateHandle(extras),
                        Injection.provideChatRepository(app), Injection.provideContactsRepository(app));
            }));

    private final SavedStateHandle state;
    private final ChatRepository chat;
    private final LiveData<List<UserEntity>> friends;
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<String> created = new MutableLiveData<>();
    public final MutableLiveData<Integer> notice = new MutableLiveData<>();

    public GroupCreateViewModel(SavedStateHandle state, ChatRepository chat, ContactsRepository contacts) {
        this.state = state;
        this.chat = chat;
        String me = contacts.currentUserId();
        friends = me == null ? new MutableLiveData<>(Collections.emptyList()) : contacts.getFriends(me);
    }

    public LiveData<List<UserEntity>> getFriends() { return friends; }
    public LiveData<ArrayList<String>> getSelected() { return state.getLiveData(K_SELECTED, new ArrayList<>()); }
    public String getTitle() { String t = state.get(K_TITLE); return t == null ? "" : t; }
    public void setTitle(String t) { state.set(K_TITLE, t); }

    public void toggle(String userId) {
        ArrayList<String> ids = new ArrayList<>(selected());
        if (!ids.remove(userId)) ids.add(userId);
        state.set(K_SELECTED, ids);
    }

    /** 0 nếu hợp lệ; ngược lại là chuỗi lỗi cần hiện. Tách riêng để test JVM được. */
    static int validate(@Nullable String title, int selectedCount) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 50) return R.string.group_name_error;
        if (selectedCount < 2) return R.string.group_min_members;
        if (1 + selectedCount > MAX_MEMBERS) return R.string.chat_error_group_full;
        return 0;
    }

    public void create() {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        List<String> ids = selected();
        int error = validate(getTitle(), ids.size());
        if (error != 0) { notice.setValue(error); return; }
        busy.setValue(true);
        Once.observe(chat.createGroup(getTitle().trim(), ids), r -> {
            busy.setValue(false);
            if (r.isSuccess() && r.data != null) created.setValue(r.data);
            else notice.setValue(ChatErrors.message(r.errorCode));
        });
    }

    public void consumeCreated() { created.setValue(null); }
    public void consumeNotice() { notice.setValue(null); }

    private List<String> selected() {
        ArrayList<String> ids = state.get(K_SELECTED);
        return ids == null ? Collections.emptyList() : ids;
    }
}
