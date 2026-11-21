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
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.databinding.FragmentGroupCreateBinding;
import java.util.List;

/** Tạo nhóm (D-10): nhập tên, chọn từ 2 bạn bè trở lên, tạo xong mở thẳng phòng chat nhóm. */
public class GroupCreateFragment extends Fragment {
    private FragmentGroupCreateBinding binding;
    private GroupCreateViewModel vm;
    private MemberSelectAdapter adapter;
    private TextWatcher watcher;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentGroupCreateBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        vm = new ViewModelProvider(this, GroupCreateViewModel.FACTORY).get(GroupCreateViewModel.class);
        NavController nav = NavHostFragment.findNavController(this);
        binding.toolbar.setNavigationOnClickListener(v -> nav.navigateUp());

        adapter = new MemberSelectAdapter(true, u -> vm.toggle(u.getId()));
        binding.friends.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.friends.setAdapter(adapter);

        binding.title.setText(vm.getTitle());
        watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                vm.setTitle(s.toString());
                binding.titleLayout.setError(null);
            }
            @Override public void afterTextChanged(Editable e) { }
        };
        binding.title.addTextChangedListener(watcher);
        binding.create.setOnClickListener(v -> vm.create());

        vm.getFriends().observe(getViewLifecycleOwner(), f -> {
            adapter.submitList(f, () -> { if (binding != null) adapter.setSelected(selected()); });
            binding.empty.setVisibility(f == null || f.isEmpty() ? View.VISIBLE : View.GONE);
        });
        vm.getSelected().observe(getViewLifecycleOwner(), ids -> {
            adapter.setSelected(ids);
            binding.selected.setText(getString(R.string.group_selected, ids.size()));
            binding.create.setText(getString(R.string.group_create_action, ids.size()));
        });
        vm.busy.observe(getViewLifecycleOwner(), b -> {
            binding.progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE);
            binding.create.setEnabled(!Boolean.TRUE.equals(b));
        });
        vm.created.observe(getViewLifecycleOwner(), id -> {
            if (id == null) return;
            vm.consumeCreated();
            Bundle args = new Bundle();
            args.putString("conversationId", id);
            nav.navigate(R.id.action_groupCreate_to_chatRoom, args);
        });
        vm.notice.observe(getViewLifecycleOwner(), res -> {
            if (res == null) return;
            vm.consumeNotice();
            // Lỗi tên hiện ngay dưới ô nhập; lỗi khác dùng Snackbar.
            if (res == R.string.group_name_error) binding.titleLayout.setError(getString(res));
            else Snackbar.make(binding.getRoot(), res, Snackbar.LENGTH_LONG).show();
        });
    }

    private List<String> selected() {
        List<String> ids = vm.getSelected().getValue();
        return ids == null ? List.of() : ids;
    }

    @Override public void onDestroyView() {
        binding.title.removeTextChangedListener(watcher);
        binding.friends.setAdapter(null);
        watcher = null; adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
