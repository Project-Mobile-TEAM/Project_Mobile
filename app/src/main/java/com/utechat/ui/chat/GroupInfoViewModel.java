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
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.di.Injection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Thông tin nhóm (D-09, D-10): đổi tên, tắt thông báo cá nhân, thêm/xóa thành viên, đổi quyền admin,
 * và rời nhóm theo 3 trường hợp: admin duy nhất còn người khác thì phải chọn người kế nhiệm; còn admin khác
 * hoặc mình là người cuối thì rời thẳng.
 */
public class GroupInfoViewModel extends ViewModel {
    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(GroupInfoViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new GroupInfoViewModel(createSavedStateHandle(extras),
                        Injection.provideChatRepository(app), Injection.provideContactsRepository(app));
            }));

    private final ChatRepository chat;
    private final String conversationId;
    private final LiveData<ConversationEntity> conversation;
    private final LiveData<List<ConversationMemberEntity>> members;
    private final LiveData<Map<String, UserEntity>> users;
    private final LiveData<List<UserEntity>> friends;
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> notice = new MutableLiveData<>();
    /** Server báo cần chọn admin mới (admin khác vừa rời cùng lúc): Fragment mở lại hộp chọn người kế nhiệm. */
    public final MutableLiveData<Boolean> handoverRequired = new MutableLiveData<>();
    public final MutableLiveData<Boolean> left = new MutableLiveData<>();

    public GroupInfoViewModel(SavedStateHandle state, ChatRepository chat, ContactsRepository contacts) {
        this.chat = chat;
        this.conversationId = state.get("conversationId");
        conversation = chat.observeConversation(conversationId);
        members = chat.observeMembers(conversationId);
        users = chat.observeAllUsersMap();
        String me = contacts.currentUserId();
        friends = me == null ? new MutableLiveData<>(Collections.emptyList()) : contacts.getFriends(me);
    }

    public LiveData<ConversationEntity> getConversation() { return conversation; }
    public LiveData<List<ConversationMemberEntity>> getMembers() { return members; }
    public LiveData<Map<String, UserEntity>> getUsers() { return users; }
    public LiveData<List<UserEntity>> getFriends() { return friends; }
    @Nullable public String meId() { return chat.currentUserId(); }

    static List<ConversationMemberEntity> active(@Nullable List<ConversationMemberEntity> all) {
        List<ConversationMemberEntity> out = new ArrayList<>();
        if (all != null) for (ConversationMemberEntity m : all) if (!m.getLeft()) out.add(m);
        return out;
    }

    static boolean isAdmin(@Nullable List<ConversationMemberEntity> all, @Nullable String userId) {
        for (ConversationMemberEntity m : active(all)) if (m.getUserId().equals(userId)) return "ADMIN".equals(m.getRole());
        return false;
    }

    /** Admin duy nhất của nhóm còn người khác: rời nhóm phải kèm người kế nhiệm. */
    static boolean needsSuccessor(@Nullable List<ConversationMemberEntity> all, @Nullable String me) {
        List<ConversationMemberEntity> act = active(all);
        int admins = 0;
        for (ConversationMemberEntity m : act) if ("ADMIN".equals(m.getRole())) admins++;
        return isAdmin(all, me) && admins == 1 && act.size() > 1;
    }

    /** Bạn bè chưa ở trong nhóm, để admin thêm vào. */
    static List<UserEntity> addable(@Nullable List<UserEntity> friends, @Nullable List<ConversationMemberEntity> all) {
        List<UserEntity> out = new ArrayList<>();
        if (friends == null) return out;
        outer:
        for (UserEntity f : friends) {
            for (ConversationMemberEntity m : active(all)) if (m.getUserId().equals(f.getId())) continue outer;
            out.add(f);
        }
        return out;
    }

    public void rename(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 50) { notice.setValue(R.string.group_name_error); return; }
        run(chat.renameGroup(conversationId, t));
    }

    /** Tắt thông báo chỉ áp dụng cho riêng mình, không sinh tin SYSTEM (D-09). */
    public void setMuted(boolean muted) { run(chat.setMuted(conversationId, muted)); }
    public void addMembers(List<String> ids) { if (!ids.isEmpty()) run(chat.addMembers(conversationId, ids)); }
    public void setRole(String userId, String role) { run(chat.updateMemberRole(conversationId, userId, role)); }
    public void remove(String userId) { run(chat.removeOrLeaveMember(conversationId, userId, null)); }

    public void leave(@Nullable String successorUserId) {
        String me = meId();
        if (me == null || Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        Once.observe(chat.removeOrLeaveMember(conversationId, me, successorUserId), r -> {
            busy.setValue(false);
            if (r.isSuccess()) left.setValue(true);
            else if ("ADMIN_HANDOVER_REQUIRED".equals(r.errorCode)) handoverRequired.setValue(true);
            else notice.setValue(ChatErrors.message(r.errorCode));
        });
    }

    public void consumeNotice() { notice.setValue(null); }
    public void consumeHandover() { handoverRequired.setValue(null); }

    private void run(LiveData<ChatResult<Boolean>> call) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        Once.observe(call, r -> {
            busy.setValue(false);
            if (!r.isSuccess()) notice.setValue(ChatErrors.message(r.errorCode));
        });
    }
}
