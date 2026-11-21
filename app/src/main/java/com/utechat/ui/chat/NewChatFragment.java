package com.utechat.ui.chat;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.model.ContactDtos;
import com.utechat.databinding.FragmentNewChatBinding;
import java.util.ArrayList;
import java.util.List;

/** Tin nhắn mới (D-01): tạo nhóm, nhắn bạn bè, hoặc tra SĐT người lạ rồi nhắn/kết bạn. */
public class NewChatFragment extends Fragment {
    private FragmentNewChatBinding binding;
    private NewChatViewModel vm;
    private MemberSelectAdapter adapter;
    private TextWatcher watcher;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentNewChatBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        vm = new ViewModelProvider(this, NewChatViewModel.FACTORY).get(NewChatViewModel.class);
        NavController nav = NavHostFragment.findNavController(this);
        binding.toolbar.setNavigationOnClickListener(v -> nav.navigateUp());

        // Lối tắt "Tạo nhóm mới": chữ màu nhấn, avatar biểu tượng nhóm (Figma).
        ConversationListAdapter.bindRow(binding.createGroup, getString(R.string.chat_create_group),
                getString(R.string.chat_create_group_hint), true, 0, 0, false);
        binding.createGroup.title.setTextColor(ContextCompat.getColor(requireContext(), R.color.ute_accent));
        binding.createGroup.getRoot().setOnClickListener(v -> nav.navigate(R.id.action_newChat_to_groupCreate));
        binding.lookupRow.getRoot().setOnClickListener(v -> vm.lookupPhone());

        adapter = new MemberSelectAdapter(false, u -> vm.openDirect(u.getId(), toDto(u)));
        binding.friends.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.friends.setAdapter(adapter);

        binding.search.setText(vm.getInput().getValue());
        watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { vm.setInput(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.search.addTextChangedListener(watcher);
        binding.search.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            vm.lookupPhone();
            return true;
        });

        vm.getFriends().observe(getViewLifecycleOwner(), f -> render());
        vm.getInput().observe(getViewLifecycleOwner(), s -> render());
        vm.lookup.observe(getViewLifecycleOwner(), s -> render());
        vm.busy.observe(getViewLifecycleOwner(), b -> binding.progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        vm.openConversation.observe(getViewLifecycleOwner(), id -> {
            if (id == null) return;
            vm.consumeOpen();
            Bundle args = new Bundle();
            args.putString("conversationId", id);
            nav.navigate(R.id.action_newChat_to_chatRoom, args);
        });
        vm.notice.observe(getViewLifecycleOwner(), res -> {
            if (res == null) return;
            vm.consumeNotice();
            Snackbar.make(binding.getRoot(), res, Snackbar.LENGTH_LONG).show();
        });
    }

    private void render() {
        if (binding == null) return;
        String input = vm.getInput().getValue() == null ? "" : vm.getInput().getValue().trim();
        String phone = NewChatViewModel.phoneOf(input);
        NewChatViewModel.LookupState lookup = vm.lookup.getValue();

        // Dòng "Tìm số điện thoại ..." chỉ hiện khi ô nhập là SĐT hợp lệ và chưa tra.
        boolean showLookupRow = phone != null && lookup == null;
        binding.lookupRow.getRoot().setVisibility(showLookupRow ? View.VISIBLE : View.GONE);
        if (showLookupRow) {
            ConversationListAdapter.bindRow(binding.lookupRow, getString(R.string.chat_lookup_phone, phone), "", true, 0, 0, false);
            binding.lookupRow.avatarGroup.setImageResource(R.drawable.ic_search);
            binding.lookupRow.preview.setVisibility(View.GONE);
        }
        renderLookup(lookup, phone);

        List<UserEntity> all = vm.getFriends().getValue();
        List<UserEntity> shown = filter(all, input, phone);
        adapter.submitList(shown);
        ((TextView) binding.friendsHeader.getRoot()).setText(getString(R.string.chat_friends_count, shown.size()));
        boolean none = shown.isEmpty();
        binding.empty.setVisibility(none ? View.VISIBLE : View.GONE);
        binding.empty.setText(all == null || all.isEmpty() ? R.string.chat_no_friends : R.string.chat_no_friend_match);
    }

    /** Thẻ kết quả tra SĐT: nhắn tin, kết bạn khi bắt buộc (CONTACT_REQUIRED), hoặc báo chặn/không tìm thấy. */
    private void renderLookup(@Nullable NewChatViewModel.LookupState s, @Nullable String phone) {
        binding.resultGroup.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s == null) return;
        ((TextView) binding.resultHeader.getRoot()).setText(R.string.chat_lookup_result);
        ContactDtos.Lookup found = s.found;
        binding.result.getRoot().setVisibility(found == null ? View.GONE : View.VISIBLE);
        binding.resultAction.setVisibility(View.GONE);
        binding.resultNote.setVisibility(View.VISIBLE);
        if (found == null) {
            binding.resultNote.setText("NOT_FOUND".equals(s.errorCode) ? getString(R.string.chat_lookup_not_found)
                    : "SELF".equals(s.errorCode) ? getString(R.string.chat_lookup_self) : getString(ChatErrors.message(s.errorCode)));
            return;
        }
        String name = found.displayName == null || found.displayName.isBlank() ? getString(R.string.chat_someone) : found.displayName;
        ConversationListAdapter.bindRow(binding.result, name, phone == null ? "" : phone, false, 0, 0, false);
        if (s.requestSent) {
            binding.resultNote.setText(R.string.chat_friend_request_sent);
        } else if (s.contactRequired) {
            binding.resultNote.setText(R.string.chat_contact_required);
            showAction(R.string.chat_send_friend_request, v -> vm.sendFriendRequest(found.id, found));
        } else if ("BLOCKED".equals(found.relationshipStatus)) {
            binding.resultNote.setText(R.string.chat_error_blocked);
        } else {
            binding.resultNote.setVisibility(View.GONE);
            showAction(R.string.chat_message, v -> vm.openDirect(found.id, found));
        }
    }

    private void showAction(int label, View.OnClickListener click) {
        binding.resultAction.setVisibility(View.VISIBLE);
        binding.resultAction.setText(label);
        binding.resultAction.setOnClickListener(click);
    }

    /** Lọc bạn bè theo tên không dấu, hoặc theo SĐT khi ô nhập là số. */
    private static List<UserEntity> filter(@Nullable List<UserEntity> all, String input, @Nullable String phone) {
        List<UserEntity> out = new ArrayList<>();
        if (all == null) return out;
        String q = MessageText.normalize(input);
        for (UserEntity u : all) {
            boolean match = phone != null ? phone.equals(u.getPhone())
                    : q == null || q.isEmpty() || (u.getDisplayName() != null && MessageText.normalize(u.getDisplayName()).contains(q));
            if (match) out.add(u);
        }
        return out;
    }

    private static ContactDtos.User toDto(UserEntity u) {
        ContactDtos.User dto = new ContactDtos.User();
        dto.id = u.getId(); dto.displayName = u.getDisplayName(); dto.avatarUrl = u.getAvatarUrl();
        return dto;
    }

    @Override public void onDestroyView() {
        binding.search.removeTextChangedListener(watcher);
        binding.friends.setAdapter(null);
        watcher = null; adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
