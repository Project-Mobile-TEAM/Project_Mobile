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
import com.utechat.data.local.MessageText;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.di.Injection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Danh sách hội thoại + tìm kiếm toàn cục; từ khóa nằm trong SavedStateHandle nên xoay màn hình không mất. */
public class ChatListViewModel extends ViewModel {
    private static final String KEY_QUERY = "query";

    public static final ViewModelProvider.Factory FACTORY = ViewModelProvider.Factory.from(
            new ViewModelInitializer<>(ChatListViewModel.class, extras -> {
                Application app = extras.get(APPLICATION_KEY);
                return new ChatListViewModel(createSavedStateHandle(extras), Injection.provideChatRepository(app));
            }));

    private final SavedStateHandle state;
    private final ChatRepository repo;
    private final LiveData<List<ConversationListItem>> conversations;
    private final LiveData<Map<String, UserEntity>> users;
    private final LiveData<WsConnectionState> wsState;
    private final LiveData<List<MessageDao.MessageSearchHit>> messageHits;

    public ChatListViewModel(SavedStateHandle state, ChatRepository repo) {
        this.state = state;
        this.repo = repo;
        conversations = repo.observeConversationSummaries();
        users = repo.observeAllUsersMap();
        wsState = repo.observeWsState();
        // FTS4 chỉ chạy khi có từ khóa; repository giới hạn theo phiên và visibleFromMessageId.
        messageHits = Transformations.switchMap(getQuery(), q -> q == null || q.isBlank()
                ? new MutableLiveData<>(Collections.emptyList())
                : repo.searchGlobalMessages(q.trim(), 50));
    }

    public LiveData<List<ConversationListItem>> getConversations() { return conversations; }
    public LiveData<Map<String, UserEntity>> getUsersMap() { return users; }
    public LiveData<WsConnectionState> getWsState() { return wsState; }
    public LiveData<List<MessageDao.MessageSearchHit>> getMessageHits() { return messageHits; }
    public LiveData<String> getQuery() { return state.getLiveData(KEY_QUERY, ""); }
    @Nullable public String meId() { return repo.currentUserId(); }

    public void setQuery(String q) {
        if (!q.equals(state.get(KEY_QUERY))) state.set(KEY_QUERY, q);
    }

    /** Hội thoại có tên khớp từ khóa, so không dấu ("an" khớp "Nguyễn Hoài An"). */
    static List<ConversationListItem> filterTitles(@Nullable List<ConversationListItem> all, @Nullable String query) {
        List<ConversationListItem> out = new ArrayList<>();
        String q = MessageText.normalize(query);
        if (all == null || q == null || q.isBlank()) return out;
        for (ConversationListItem item : all) {
            String title = MessageText.normalize(item.getEffectiveTitle());
            if (title != null && title.contains(q.trim())) out.add(item);
        }
        return out;
    }
}
