package com.utechat.ui.chat;

import static androidx.lifecycle.SavedStateHandleSupport.createSavedStateHandle;
import static androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY;

import android.app.Application;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.ViewModelInitializer;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ContactsRepository;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.di.Injection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phòng chat: cửa sổ tin nhắn (windowLimit) mở rộng dần khi cuộn lên hoặc nhảy tới kết quả tìm kiếm (D-13),
 * gửi/gửi lại tin, typing, và báo đã đọc chỉ khi màn đang RESUMED (D-11).
 * Mọi trạng thái người dùng thấy (hội thoại, cửa sổ, từ khóa, vị trí kết quả) nằm trong SavedStateHandle để xoay màn hình không mất.
 */
public class ChatRoomViewModel extends ViewModel {
    static final int PAGE = 50;
    private static final String K_CONV = "conversationId", K_PEER = "peerUserId", K_TARGET = "targetMessageLocalId",
            K_WINDOW = "windowLimit", K_QUERY = "roomQuery", K_MATCH = "matchIndex", K_TARGET_DONE = "targetHandled";

    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(ChatRoomViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new ChatRoomViewModel(createSavedStateHandle(extras), Injection.provideChatRepository(app),
                        Injection.provideContactsRepository(app));
            }));

    private final SavedStateHandle state;
    private final ChatRepository repo;
    private final ContactsRepository contacts;
    /** true khi đã gửi lời mời kết bạn từ biểu ngữ CONTACT_REQUIRED (ẩn nút, hiện "Đã gửi lời mời"). */
    public final MutableLiveData<Boolean> requestSent = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> notice = new MutableLiveData<>();
    private final LiveData<String> conversationId;
    private final LiveData<List<MessageEntity>> messages;
    private final LiveData<ConversationEntity> conversation;
    private final LiveData<List<ConversationMemberEntity>> members;
    private final LiveData<Set<String>> typing;
    private final LiveData<Map<String, UserEntity>> users; // Giữ một instance để Fragment đọc getValue() được.
    /** Lỗi chính sách khi mở chat 1-1 bằng peerUserId (BLOCKED / CONTACT_REQUIRED). */
    public final MutableLiveData<String> openError = new MutableLiveData<>();
    /** localId cần cuộn tới khi nó đã có trong danh sách; Fragment cuộn xong thì consumeScroll(). */
    public final MutableLiveData<Long> pendingScroll = new MutableLiveData<>();
    /** Kết quả tìm trong phòng: localId từ mới nhất đến cũ nhất. */
    public final MutableLiveData<List<Long>> matches = new MutableLiveData<>(Collections.emptyList());
    public final MutableLiveData<Boolean> loadingMore = new MutableLiveData<>(false);
    private boolean endReached;
    private long readWatermark; // serverId cao nhất đã gửi báo đọc, để không gửi lặp khi cuộn/layout liên tục.

    public ChatRoomViewModel(SavedStateHandle state, ChatRepository repo, ContactsRepository contacts) {
        this.state = state;
        this.repo = repo;
        this.contacts = contacts;
        conversationId = state.getLiveData(K_CONV);
        LiveData<Integer> window = state.getLiveData(K_WINDOW, PAGE);
        messages = Transformations.switchMap(conversationId, id -> id == null
                ? new MutableLiveData<>(Collections.emptyList())
                : Transformations.switchMap(window, limit -> repo.observeVisibleMessages(id, limit)));
        conversation = Transformations.switchMap(conversationId, id -> id == null ? new MutableLiveData<>() : repo.observeConversation(id));
        members = Transformations.switchMap(conversationId, id -> id == null
                ? new MutableLiveData<>(Collections.emptyList()) : repo.observeMembers(id));
        typing = Transformations.switchMap(conversationId, id -> id == null
                ? new MutableLiveData<>(Collections.emptySet()) : repo.observeTypingUsers(id));
        users = repo.observeAllUsersMap();

        // Vào từ deep link utechat://chat/direct/{peerUserId} (D-02): hỏi server hội thoại 1-1, có rồi thì dùng lại.
        String peer = state.get(K_PEER);
        if (state.get(K_CONV) == null && peer != null) {
            Once.observe(repo.openOrCreateDirect(peer), r -> {
                if (r.isSuccess() && r.data != null) state.set(K_CONV, r.data);
                else openError.setValue(r.errorCode);
            });
        }
        // Mở từ kết quả tìm kiếm toàn cục: cuộn tới tin đó đúng một lần (không lặp lại sau xoay màn hình).
        Long target = state.get(K_TARGET);
        if (target != null && target > 0 && !Boolean.TRUE.equals(state.get(K_TARGET_DONE))) {
            state.set(K_TARGET_DONE, true);
            requestScroll(target);
        }
    }

    public LiveData<String> getConversationId() { return conversationId; }
    public LiveData<List<MessageEntity>> getMessages() { return messages; }
    public LiveData<ConversationEntity> getConversation() { return conversation; }
    public LiveData<List<ConversationMemberEntity>> getMembers() { return members; }
    public LiveData<Set<String>> getTyping() { return typing; }
    public LiveData<Map<String, UserEntity>> getUsers() { return users; }
    public LiveData<WsConnectionState> getWsState() { return repo.observeWsState(); }
    @Nullable public String meId() { return repo.currentUserId(); }
    public String getQuery() { String q = state.get(K_QUERY); return q == null ? "" : q; }
    public int getMatchIndex() { Integer i = state.get(K_MATCH); return i == null ? 0 : i; }

    /** Bị xóa/rời nhóm thì chỉ được xem lại tin cũ (D-12). */
    public static boolean isReadOnly(@Nullable List<ConversationMemberEntity> members, @Nullable String meId) {
        if (members == null || meId == null) return false;
        for (ConversationMemberEntity m : members) if (meId.equals(m.getUserId())) return m.getLeft();
        return false;
    }

    /**
     * Biểu ngữ chính sách (D-04): lỗi khi mở chat, hoặc tin gửi đi mới nhất của mình FAILED vì BLOCKED/CONTACT_REQUIRED.
     * Gửi được một tin sau đó thì biểu ngữ tự mất vì tin mới nhất không còn lỗi.
     */
    @Nullable public static String policyBanner(@Nullable String openError, @Nullable List<MessageEntity> list, @Nullable String meId) {
        if (openError != null && isPolicy(openError)) return openError;
        if (list == null || meId == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            MessageEntity m = list.get(i);
            if (!meId.equals(m.getSenderId())) continue;
            return "FAILED".equals(m.getStatus()) && isPolicy(m.getErrorCode()) ? m.getErrorCode() : null;
        }
        return null;
    }

    private static boolean isPolicy(@Nullable String code) { return "BLOCKED".equals(code) || "CONTACT_REQUIRED".equals(code); }

    /** Cuộn tới tin; nếu tin nằm ngoài cửa sổ đang hiện thì nới windowLimit để Room trả thêm tin cũ. */
    public void requestScroll(long localId) {
        pendingScroll.setValue(localId);
        String id = state.get(K_CONV);
        if (id == null || contains(messages.getValue(), localId)) return;
        Once.observe(repo.computeWindowLimitForMessage(id, localId, window()), limit -> {
            if (limit > window()) state.set(K_WINDOW, limit);
        });
    }

    public void consumeScroll() { pendingScroll.setValue(null); }

    public void setSearchQuery(String q) {
        state.set(K_QUERY, q);
        String id = state.get(K_CONV);
        if (id == null || q.isBlank()) { matches.setValue(Collections.emptyList()); return; }
        Once.observe(repo.searchInConversation(id, q.trim()), list -> {
            if (!q.equals(state.get(K_QUERY))) return; // Kết quả của từ khóa cũ về muộn thì bỏ.
            List<Long> ids = new ArrayList<>();
            for (MessageEntity m : list) ids.add(m.getLocalId());
            matches.setValue(ids);
            state.set(K_MATCH, 0);
            if (!ids.isEmpty()) requestScroll(ids.get(0));
        });
    }

    /** step = +1 sang kết quả cũ hơn (mũi tên lên), -1 sang kết quả mới hơn (mũi tên xuống). */
    public void stepMatch(int step) {
        List<Long> ids = matches.getValue();
        if (ids == null || ids.isEmpty()) return;
        int next = Math.max(0, Math.min(ids.size() - 1, getMatchIndex() + step));
        state.set(K_MATCH, next);
        matches.setValue(ids); // Báo Fragment vẽ lại bộ đếm.
        requestScroll(ids.get(next));
    }

    /**
     * Cuộn lên đầu: trước hết nới cửa sổ để lộ tin đã có trong máy; hết cache mới gọi server lấy trang cũ hơn.
     * Nhóm đã rời (read-only) không được tải lịch sử server.
     */
    public void loadOlder(boolean readOnly) {
        String id = state.get(K_CONV);
        List<MessageEntity> list = messages.getValue();
        if (id == null || list == null || Boolean.TRUE.equals(loadingMore.getValue())) return;
        if (list.size() >= window()) { state.set(K_WINDOW, window() + PAGE); return; }
        if (readOnly || endReached) return;
        Long oldest = null;
        for (MessageEntity m : list) if (m.getServerId() != null) { oldest = m.getServerId(); break; }
        loadingMore.setValue(true);
        Once.observe(repo.loadOlderMessages(id, oldest, PAGE), r -> {
            loadingMore.setValue(false);
            if (!r.isSuccess() || r.data == null) return;
            if (r.data < PAGE) endReached = true;
            if (r.data > 0) state.set(K_WINDOW, window() + r.data);
        });
    }

    public void sendText(String text) {
        String id = state.get(K_CONV);
        if (id == null || text == null || text.isBlank()) return;
        repo.enqueueTextMessage(id, text.trim());
        repo.sendTypingStop(id);
    }

    /**
     * Người kia chỉ nhận tin từ bạn bè (CONTACT_REQUIRED): gửi lời mời kết bạn ngay trong phòng chat,
     * dùng lại ContactsRepository của Phase 4. Lời mời đã chờ sẵn cũng coi như đã gửi.
     */
    public void sendFriendRequest(@Nullable String peerUserId) {
        if (peerUserId == null || Boolean.TRUE.equals(requestSent.getValue())) return;
        Once.observe(contacts.act("send", peerUserId, null), r -> {
            if (r.isSuccess() || "REQUEST_ALREADY_PENDING".equals(r.errorCode)) {
                requestSent.setValue(true);
                notice.setValue(com.utechat.R.string.chat_friend_request_sent);
            } else {
                notice.setValue(ChatErrors.message(r.errorCode));
            }
        });
    }

    /** Người còn lại của chat 1-1: lấy từ deep link, nếu không thì từ danh sách thành viên. */
    @Nullable public static String peerOf(@Nullable String deepLinkPeer, @Nullable List<ConversationMemberEntity> members, @Nullable String meId) {
        if (deepLinkPeer != null) return deepLinkPeer;
        if (members != null) for (ConversationMemberEntity m : members) if (!m.getUserId().equals(meId)) return m.getUserId();
        return null;
    }

    @Nullable public String deepLinkPeer() { return state.get(K_PEER); }
    public void consumeNotice() { notice.setValue(null); }

    public void retryFailed(String clientId) { repo.retryFailedMessage(clientId); }
    public void deleteFailed(String clientId) { repo.deleteFailedMessage(clientId); }

    public void onTextChanged(String text) {
        String id = state.get(K_CONV);
        if (id == null) return;
        if (text.isEmpty()) repo.sendTypingStop(id); else repo.sendTypingStart(id);
    }

    public void stopTyping() {
        String id = state.get(K_CONV);
        if (id != null) repo.sendTypingStop(id);
    }

    /**
     * Báo đã đọc tới serverId cao nhất ĐANG HIỆN trên màn hình (Fragment tính từ viewport),
     * chỉ khi RESUMED, không read-only, và chỉ khi cao hơn mốc đã gửi.
     */
    public void markVisibleRead(long visibleUpToServerId, boolean resumed, boolean readOnly) {
        String id = state.get(K_CONV);
        if (!resumed || readOnly || id == null || visibleUpToServerId <= readWatermark) return;
        readWatermark = visibleUpToServerId;
        repo.markConversationRead(id, visibleUpToServerId);
    }

    private int window() { Integer w = state.get(K_WINDOW); return w == null ? PAGE : w; }

    private static boolean contains(@Nullable List<MessageEntity> list, long localId) {
        if (list != null) for (MessageEntity m : list) if (m.getLocalId() == localId) return true;
        return false;
    }

}
