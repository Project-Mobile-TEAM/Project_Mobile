package com.utechat.ui.chat;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.utechat.R;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.databinding.FragmentChatListBinding;
import java.util.ArrayList;
import java.util.List;

/** Tab Tin nhắn: danh sách hội thoại, biểu ngữ kết nối (D-08) và tìm kiếm toàn cục (D-13). */
public class ChatListFragment extends Fragment {
    private FragmentChatListBinding binding; // Binding chỉ sống trong vòng đời View của Fragment.
    private ChatListViewModel vm;
    private ConversationListAdapter listAdapter;
    private GlobalSearchAdapter searchAdapter;
    private TextWatcher watcher;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentChatListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        vm = new ViewModelProvider(this, ChatListViewModel.FACTORY).get(ChatListViewModel.class);
        String meId = vm.meId();
        listAdapter = new ConversationListAdapter(meId, this::open);
        searchAdapter = new GlobalSearchAdapter(meId, new GlobalSearchAdapter.Listener() {
            @Override public void openConversation(ConversationListItem item) { open(item); }
            @Override public void openMessage(MessageDao.MessageSearchHit hit) {
                // Truyền localId để phòng chat cuộn tới đúng tin vừa tìm thấy.
                ChatNavigation.openConversation(NavHostFragment.findNavController(ChatListFragment.this),
                        hit.message.getConversationId(), hit.message.getLocalId());
            }
        });
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));

        binding.search.setText(vm.getQuery().getValue());
        watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.setQuery(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.search.addTextChangedListener(watcher);
        binding.clear.setOnClickListener(v -> binding.search.setText(""));
        binding.fab.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_chatList_to_newChat));

        vm.getWsState().observe(getViewLifecycleOwner(), this::renderBanner);
        vm.getUsersMap().observe(getViewLifecycleOwner(), users -> {
            listAdapter.setUsers(users);
            searchAdapter.setUsers(users);
            render();
        });
        vm.getConversations().observe(getViewLifecycleOwner(), list -> render());
        vm.getQuery().observe(getViewLifecycleOwner(), q -> render());
        vm.getMessageHits().observe(getViewLifecycleOwner(), hits -> render());
    }

    private void open(ConversationListItem item) {
        ChatNavigation.openConversation(NavHostFragment.findNavController(this), item.conversation.getId(), -1L);
    }

    /** Hiện biểu ngữ khi chưa kết nối hoặc đang đồng bộ lại; ẩn ngay khi CONNECTED. */
    private void renderBanner(@Nullable WsConnectionState s) {
        if (binding == null) return;
        boolean show = s != null && s != WsConnectionState.CONNECTED;
        binding.ws.wsBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.ws.wsBannerText.setText(s == WsConnectionState.DISCONNECTED
                ? R.string.chat_ws_disconnected : R.string.chat_ws_reconnecting);
    }

    private void render() {
        if (binding == null) return;
        String q = vm.getQuery().getValue() == null ? "" : vm.getQuery().getValue().trim();
        binding.clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        List<ConversationListItem> all = vm.getConversations().getValue();
        if (q.isEmpty()) {
            if (binding.list.getAdapter() != listAdapter) binding.list.setAdapter(listAdapter);
            listAdapter.submitList(all);
            boolean empty = all == null || all.isEmpty();
            showEmpty(empty, R.drawable.ic_tab_chat, getString(R.string.chat_empty_title), getString(R.string.chat_empty_hint));
            binding.fab.setVisibility(View.VISIBLE);
            return;
        }
        // Tìm kiếm: hai phần tách biệt, phần tin nhắn ghi rõ chỉ trên thiết bị.
        List<GlobalSearchAdapter.Row> rows = new ArrayList<>();
        List<ConversationListItem> titles = ChatListViewModel.filterTitles(all, q);
        if (!titles.isEmpty()) {
            rows.add(GlobalSearchAdapter.Row.header(getString(R.string.chat_section_conversations)));
            for (ConversationListItem c : titles) rows.add(GlobalSearchAdapter.Row.conversation(c));
        }
        List<MessageDao.MessageSearchHit> hits = vm.getMessageHits().getValue();
        if (hits != null && !hits.isEmpty()) {
            rows.add(GlobalSearchAdapter.Row.header(getString(R.string.chat_section_messages)));
            for (MessageDao.MessageSearchHit h : hits) rows.add(GlobalSearchAdapter.Row.message(h));
        }
        if (binding.list.getAdapter() != searchAdapter) binding.list.setAdapter(searchAdapter);
        searchAdapter.submit(rows, q);
        showEmpty(rows.isEmpty(), R.drawable.ic_search, getString(R.string.chat_search_empty_title),
                getString(R.string.chat_search_empty_hint, q));
        binding.fab.setVisibility(View.GONE);
    }

    private void showEmpty(boolean show, int icon, String title, String hint) {
        binding.empty.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.emptyIcon.setImageResource(icon);
        binding.emptyTitle.setText(title);
        binding.emptyHint.setText(hint);
    }

    @Override public void onDestroyView() {
        binding.search.removeTextChangedListener(watcher);
        binding.list.setAdapter(null);
        watcher = null; listAdapter = null; searchAdapter = null;
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
