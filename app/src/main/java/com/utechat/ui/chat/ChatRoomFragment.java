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
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.utechat.R;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.sync.WsConnectionState;
import com.utechat.databinding.FragmentChatRoomBinding;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phòng chat (05-08): tin nhắn thời gian thực/offline, mốc ngày, trạng thái gửi, gửi lại tin lỗi (D-05),
 * biểu ngữ chặn/chỉ-bạn-bè (D-04), chỉ đọc khi đã rời nhóm (D-12), tìm trong phòng (D-13) và báo đã đọc khi RESUMED (D-11).
 */
public class ChatRoomFragment extends Fragment {
    private FragmentChatRoomBinding binding;
    private ChatRoomViewModel vm;
    private MessageAdapter adapter;
    private LinearLayoutManager lm;
    private TextWatcher inputWatcher, queryWatcher;
    private boolean readOnly;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentChatRoomBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        // SavedStateHandle nhận sẵn conversationId / peerUserId / targetMessageLocalId từ arguments của nav_chat.
        vm = new ViewModelProvider(this, ChatRoomViewModel.FACTORY).get(ChatRoomViewModel.class);
        String meId = vm.meId();
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.toolbar.inflateMenu(R.menu.menu_chat_room);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_search_room) { openSearch(); return true; }
            if (item.getItemId() == R.id.action_group_info) {
                Bundle args = new Bundle();
                args.putString("conversationId", vm.getConversationId().getValue());
                NavHostFragment.findNavController(this).navigate(R.id.action_chatRoom_to_groupInfo, args);
                return true;
            }
            return false;
        });

        adapter = new MessageAdapter(meId, this::showFailedDialog);
        lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true); // Tin mới nhất nằm sát khung soạn, như mọi app chat.
        binding.messages.setLayoutManager(lm);
        binding.messages.setAdapter(adapter);
        binding.messages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy < 0 && lm.findFirstVisibleItemPosition() == 0) vm.loadOlder(readOnly);
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) markVisibleRead();
            }
        });

        // Khung soạn: gõ thì báo "đang nhập", gửi thì xóa ô.
        inputWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.onTextChanged(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.input.addTextChangedListener(inputWatcher);
        binding.send.setOnClickListener(v -> {
            String text = binding.input.getText().toString();
            if (text.isBlank()) return;
            vm.sendText(text);
            binding.input.setText("");
        });

        bindSearch();
        binding.policyAction.setOnClickListener(v -> vm.sendFriendRequest(
                ChatRoomViewModel.peerOf(vm.deepLinkPeer(), vm.getMembers().getValue(), vm.meId())));
        vm.requestSent.observe(getViewLifecycleOwner(), b -> renderComposer());
        vm.notice.observe(getViewLifecycleOwner(), res -> {
            if (res == null) return;
            vm.consumeNotice();
            com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), res,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
        });

        vm.getWsState().observe(getViewLifecycleOwner(), this::renderBanner);
        vm.getUsers().observe(getViewLifecycleOwner(), users -> { adapter.setUsers(users); renderHeader(); });
        vm.getConversation().observe(getViewLifecycleOwner(), c -> {
            adapter.setGroup(c != null && "GROUP".equals(c.getType()));
            renderHeader();
        });
        vm.getMembers().observe(getViewLifecycleOwner(), m -> {
            readOnly = ChatRoomViewModel.isReadOnly(m, meId);
            renderHeader();
            renderComposer();
        });
        vm.getTyping().observe(getViewLifecycleOwner(), t -> renderHeader());
        vm.openError.observe(getViewLifecycleOwner(), e -> renderComposer());
        vm.loadingMore.observe(getViewLifecycleOwner(), b -> binding.loadingMore.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        vm.getMessages().observe(getViewLifecycleOwner(), this::submit);
        vm.pendingScroll.observe(getViewLifecycleOwner(), id -> scrollToPending());
    }

    private void submit(List<MessageEntity> list) {
        boolean atBottom = adapter.getItemCount() == 0 || lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
        String me = vm.meId();
        boolean newMine = list != null && !list.isEmpty() && me != null && me.equals(list.get(list.size() - 1).getSenderId())
                && adapter.getItemCount() < list.size();
        adapter.submitList(list, () -> {
            if (binding == null) return;
            // Ưu tiên nhảy tới kết quả tìm kiếm; nếu không, chỉ tự cuộn xuống khi đang ở đáy hoặc mình vừa gửi.
            if (!scrollToPending() && (atBottom || newMine) && adapter.getItemCount() > 0) {
                binding.messages.scrollToPosition(adapter.getItemCount() - 1);
            }
            renderComposer();
            binding.messages.post(this::markVisibleRead);
        });
    }

    /** Cuộn tới tin đang chờ nếu nó đã có trong danh sách; chưa có thì ViewModel đang nới cửa sổ. */
    private boolean scrollToPending() {
        Long target = vm.pendingScroll.getValue();
        if (binding == null || target == null) return false;
        int i = adapter.indexOf(target);
        if (i < 0) return false;
        lm.scrollToPositionWithOffset(i, binding.messages.getHeight() / 3);
        adapter.setHighlightedLocalId(target);
        vm.consumeScroll();
        return true;
    }

    /**
     * Báo đã đọc tới serverId lớn nhất trong các dòng đang thật sự hiện trên màn hình.
     * Đang cuộn lên xem tin cũ thì các tin mới bên dưới vẫn là chưa đọc.
     */
    private void markVisibleRead() {
        if (binding == null || adapter.getItemCount() == 0) return;
        int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;
        long max = 0;
        for (int i = first; i <= last && i < adapter.getItemCount(); i++) {
            Long id = adapter.getCurrentList().get(i).getServerId();
            if (id != null && id > max) max = id;
        }
        vm.markVisibleRead(max, isResumed(), readOnly);
    }

    private void renderHeader() {
        if (binding == null) return;
        ConversationEntity c = vm.getConversation().getValue();
        Map<String, UserEntity> users = vm.getUsers().getValue();
        List<ConversationMemberEntity> members = vm.getMembers().getValue();
        String me = vm.meId();
        boolean group = c != null && "GROUP".equals(c.getType());
        String title = c != null && c.getTitle() != null && !c.getTitle().isBlank() ? c.getTitle() : null;
        int active = 0;
        if (members != null) for (ConversationMemberEntity m : members) {
            if (!m.getLeft()) active++;
            // Chat 1-1 không có tiêu đề: lấy tên người còn lại.
            if (title == null && !group && !m.getUserId().equals(me) && users != null && users.get(m.getUserId()) != null) {
                title = users.get(m.getUserId()).getDisplayName();
            }
        }
        binding.toolbar.setTitle(title == null ? "" : title);
        Set<String> typing = vm.getTyping().getValue();
        boolean othersTyping = false;
        if (typing != null) for (String id : typing) if (!id.equals(me)) othersTyping = true;
        binding.toolbar.setSubtitle(othersTyping ? getString(R.string.chat_typing)
                : group ? getResources().getQuantityString(R.plurals.group_member_count, active, active) : null);
        binding.toolbar.getMenu().findItem(R.id.action_group_info).setVisible(group);
    }

    private void renderComposer() {
        if (binding == null) return;
        String policy = ChatRoomViewModel.policyBanner(vm.openError.getValue(), vm.getMessages().getValue(), vm.meId());
        binding.policyBanner.setVisibility(policy == null ? View.GONE : View.VISIBLE);
        boolean contactRequired = "CONTACT_REQUIRED".equals(policy);
        boolean sent = Boolean.TRUE.equals(vm.requestSent.getValue());
        if (policy != null) binding.policyText.setText(!contactRequired ? R.string.chat_blocked_banner
                : sent ? R.string.chat_friend_request_sent : R.string.chat_contact_required);
        binding.policyAction.setVisibility(contactRequired && !sent ? View.VISIBLE : View.GONE);
        // Chưa mở được hội thoại (bị chặn/cần kết bạn) hoặc đã rời nhóm thì không cho soạn tin.
        boolean noConversation = vm.getConversationId().getValue() == null && vm.openError.getValue() != null;
        binding.readOnlyBar.setVisibility(readOnly ? View.VISIBLE : View.GONE);
        binding.composer.setVisibility(readOnly || noConversation ? View.GONE : View.VISIBLE);
    }

    private void renderBanner(@Nullable WsConnectionState s) {
        if (binding == null) return;
        binding.ws.wsBanner.setVisibility(s != null && s != WsConnectionState.CONNECTED ? View.VISIBLE : View.GONE);
        binding.ws.wsBannerText.setText(s == WsConnectionState.DISCONNECTED ? R.string.chat_ws_disconnected : R.string.chat_ws_reconnecting);
    }

    private void bindSearch() {
        if (!vm.getQuery().isEmpty()) binding.roomSearch.setVisibility(View.VISIBLE);
        binding.roomQuery.setText(vm.getQuery());
        queryWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.setSearchQuery(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.roomQuery.addTextChangedListener(queryWatcher);
        binding.roomPrev.setOnClickListener(v -> vm.stepMatch(1));
        binding.roomNext.setOnClickListener(v -> vm.stepMatch(-1));
        binding.roomClose.setOnClickListener(v -> {
            binding.roomQuery.setText("");
            binding.roomSearch.setVisibility(View.GONE);
            adapter.setHighlightedLocalId(-1);
        });
        vm.matches.observe(getViewLifecycleOwner(), ids -> {
            boolean any = ids != null && !ids.isEmpty();
            binding.roomCounter.setText(vm.getQuery().isBlank() ? ""
                    : getString(R.string.chat_search_counter, any ? vm.getMatchIndex() + 1 : 0, any ? ids.size() : 0));
            binding.roomPrev.setEnabled(any);
            binding.roomNext.setEnabled(any);
        });
    }

    private void openSearch() {
        binding.roomSearch.setVisibility(View.VISIBLE);
        binding.roomQuery.requestFocus();
    }

    /** D-05: chạm tin lỗi thì chọn Gửi lại (giữ clientId, server không tạo bản trùng) hoặc Xóa tin lỗi. */
    private void showFailedDialog(MessageEntity m) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chat_failed_title)
                .setMessage(ChatErrors.message(m.getErrorCode()))
                .setPositiveButton(R.string.chat_resend, (d, w) -> vm.retryFailed(m.getClientId()))
                .setNegativeButton(R.string.chat_delete_failed, (d, w) -> vm.deleteFailed(m.getClientId()))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    @Override public void onResume() {
        super.onResume();
        if (binding != null) binding.messages.post(this::markVisibleRead);
    }

    @Override public void onPause() {
        vm.stopTyping(); // Rời màn hình thì người kia không còn thấy "Đang nhập…".
        super.onPause();
    }

    @Override public void onDestroyView() {
        binding.input.removeTextChangedListener(inputWatcher);
        binding.roomQuery.removeTextChangedListener(queryWatcher);
        binding.messages.setAdapter(null);
        inputWatcher = null; queryWatcher = null; adapter = null; lm = null;
        super.onDestroyView();
        binding = null;
    }
}
