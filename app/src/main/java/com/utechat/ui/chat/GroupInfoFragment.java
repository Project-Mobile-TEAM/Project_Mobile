package com.utechat.ui.chat;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.utechat.R;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.databinding.FragmentGroupInfoBinding;
import com.utechat.databinding.ItemSettingRowBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thông tin nhóm (D-09, D-10, D-12): hàng chỉ dành cho admin tự ẩn với thành viên thường. */
public class GroupInfoFragment extends Fragment {
    private FragmentGroupInfoBinding binding;
    private GroupInfoViewModel vm;
    private GroupMembersAdapter adapter;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentGroupInfoBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        vm = new ViewModelProvider(this, GroupInfoViewModel.FACTORY).get(GroupInfoViewModel.class);
        NavController nav = NavHostFragment.findNavController(this);
        binding.toolbar.setNavigationOnClickListener(v -> nav.navigateUp());

        row(binding.rowRename, R.drawable.ic_edit, getString(R.string.group_rename), false);
        binding.rowRename.chevron.setVisibility(View.VISIBLE);
        binding.rowRename.getRoot().setOnClickListener(v -> showRename());
        row(binding.rowMute, R.drawable.ic_bell, getString(R.string.group_mute), false);
        binding.rowMute.toggle.setVisibility(View.VISIBLE);
        binding.rowMute.getRoot().setOnClickListener(v -> {
            ConversationEntity c = vm.getConversation().getValue();
            if (c != null) vm.setMuted(!c.getMuted());
        });
        row(binding.rowAdd, R.drawable.ic_people, getString(R.string.group_add_members), false);
        binding.rowAdd.title.setTextColor(ContextCompat.getColor(requireContext(), R.color.ute_accent));
        binding.rowAdd.getRoot().setOnClickListener(v -> showAddMembers());
        row(binding.rowLeave, R.drawable.ic_close, getString(R.string.group_leave), true);
        binding.rowLeave.getRoot().setOnClickListener(v -> startLeave());

        adapter = new GroupMembersAdapter(this::showMemberActions);
        binding.members.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.members.setAdapter(adapter);

        vm.getConversation().observe(getViewLifecycleOwner(), c -> render());
        vm.getMembers().observe(getViewLifecycleOwner(), m -> render());
        vm.getUsers().observe(getViewLifecycleOwner(), u -> render());
        vm.getFriends().observe(getViewLifecycleOwner(), f -> { });
        vm.busy.observe(getViewLifecycleOwner(), b -> binding.progress.setVisibility(Boolean.TRUE.equals(b) ? View.VISIBLE : View.GONE));
        vm.notice.observe(getViewLifecycleOwner(), res -> {
            if (res == null) return;
            vm.consumeNotice();
            Snackbar.make(binding.getRoot(), res, Snackbar.LENGTH_LONG).show();
        });
        vm.handoverRequired.observe(getViewLifecycleOwner(), b -> {
            if (!Boolean.TRUE.equals(b)) return;
            vm.consumeHandover();
            showSuccessorPicker();
        });
        // Rời nhóm xong thì về thẳng danh sách chat; phòng chat của nhóm này giờ chỉ đọc.
        vm.left.observe(getViewLifecycleOwner(), b -> { if (Boolean.TRUE.equals(b)) nav.popBackStack(R.id.chatListFragment, false); });
    }

    private void render() {
        if (binding == null) return;
        ConversationEntity c = vm.getConversation().getValue();
        List<ConversationMemberEntity> all = vm.getMembers().getValue();
        String me = vm.meId();
        List<ConversationMemberEntity> active = GroupInfoViewModel.active(all);
        boolean admin = GroupInfoViewModel.isAdmin(all, me);
        boolean meActive = false;
        for (ConversationMemberEntity m : active) if (m.getUserId().equals(me)) meActive = true;

        String title = c == null || c.getTitle() == null ? "" : c.getTitle();
        binding.toolbar.setTitle(R.string.group_info);
        binding.groupTitle.setText(title);
        binding.groupCount.setText(getResources().getQuantityString(R.plurals.group_member_count, active.size(), active.size()));
        binding.rowMute.toggle.setChecked(c != null && c.getMuted());
        binding.rowRename.getRoot().setVisibility(admin ? View.VISIBLE : View.GONE);
        binding.rowAdd.getRoot().setVisibility(admin ? View.VISIBLE : View.GONE);
        binding.rowLeave.getRoot().setVisibility(meActive ? View.VISIBLE : View.GONE);

        List<GroupMembersAdapter.Row> rows = new ArrayList<>();
        for (ConversationMemberEntity m : active) {
            boolean isMe = m.getUserId().equals(me);
            String name = isMe ? getString(R.string.group_you_suffix, name(m.getUserId())) : name(m.getUserId());
            rows.add(new GroupMembersAdapter.Row(m.getUserId(), name, "ADMIN".equals(m.getRole()), admin && !isMe));
        }
        // Mình đứng đầu, rồi admin, rồi theo tên (giống Figma).
        rows.sort((a, b) -> {
            boolean am = a.userId().equals(me), bm = b.userId().equals(me);
            if (am != bm) return am ? -1 : 1;
            if (a.admin() != b.admin()) return a.admin() ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });
        adapter.submitList(rows);
    }

    private String name(String userId) {
        Map<String, UserEntity> users = vm.getUsers().getValue();
        UserEntity u = users == null ? null : users.get(userId);
        return u == null || u.getDisplayName() == null ? getString(R.string.chat_someone) : u.getDisplayName();
    }

    private void row(ItemSettingRowBinding row, @DrawableRes int icon, String title, boolean danger) {
        row.title.setText(title);
        row.icon.setImageResource(icon);
        if (danger) {
            int red = ContextCompat.getColor(requireContext(), R.color.ute_error);
            row.title.setTextColor(red);
            row.icon.setImageTintList(ColorStateList.valueOf(red));
        }
    }

    private void showRename() {
        ConversationEntity c = vm.getConversation().getValue();
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setText(c == null ? "" : c.getTitle());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(50)});
        input.setHint(R.string.group_name);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.group_rename)
                .setView(padded(input))
                .setPositiveButton(android.R.string.ok, (d, w) -> vm.rename(String.valueOf(input.getText())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddMembers() {
        List<UserEntity> candidates = GroupInfoViewModel.addable(vm.getFriends().getValue(), vm.getMembers().getValue());
        if (candidates.isEmpty()) {
            Snackbar.make(binding.getRoot(), R.string.group_no_addable, Snackbar.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) names[i] = candidates.get(i).getDisplayName();
        boolean[] checked = new boolean[names.length];
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.group_add_members)
                .setMultiChoiceItems(names, checked, (d, i, on) -> checked[i] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) ids.add(candidates.get(i).getId());
                    vm.addMembers(ids);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMemberActions(GroupMembersAdapter.Row row, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, row.admin() ? R.string.group_demote : R.string.group_promote);
        menu.getMenu().add(0, 2, 1, R.string.group_remove_member);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) vm.setRole(row.userId(), row.admin() ? "MEMBER" : "ADMIN");
            else new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.group_remove_confirm, row.name()))
                    .setPositiveButton(R.string.group_remove_member, (d, w) -> vm.remove(row.userId()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        menu.show();
    }

    /** D-10: admin duy nhất còn người khác thì chọn người kế nhiệm; ngược lại chỉ xác nhận rồi rời. */
    private void startLeave() {
        if (GroupInfoViewModel.needsSuccessor(vm.getMembers().getValue(), vm.meId())) { showSuccessorPicker(); return; }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.group_leave)
                .setMessage(R.string.group_leave_confirm)
                .setPositiveButton(R.string.group_leave, (d, w) -> vm.leave(null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSuccessorPicker() {
        String me = vm.meId();
        List<String> ids = new ArrayList<>();
        for (ConversationMemberEntity m : GroupInfoViewModel.active(vm.getMembers().getValue())) if (!m.getUserId().equals(me)) ids.add(m.getUserId());
        if (ids.isEmpty()) { vm.leave(null); return; }
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = name(ids.get(i));
        int[] choice = {0};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.group_pick_admin_title)
                .setSingleChoiceItems(names, 0, (d, i) -> choice[0] = i)
                .setPositiveButton(R.string.group_pick_admin_confirm, (d, w) -> vm.leave(ids.get(choice[0])))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private View padded(View v) {
        FrameLayout box = new FrameLayout(requireContext());
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v);
        return box;
    }

    @Override public void onDestroyView() {
        binding.members.setAdapter(null);
        adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
