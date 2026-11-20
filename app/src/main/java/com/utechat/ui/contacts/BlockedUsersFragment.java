package com.utechat.ui.contacts;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.utechat.R;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.model.ContactDtos.User;
import com.utechat.databinding.FragmentBlockedUsersBinding;
import java.util.*;

/** Mục 19: danh sách chặn đọc từ Room (xem được khi offline), Bỏ chặn có hộp thoại xác nhận. */
public class BlockedUsersFragment extends Fragment {
    private FragmentBlockedUsersBinding binding;
    private ContactsViewModel vm;
    private ContactRowsAdapter adapter;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state) {
        binding=FragmentBlockedUsersBinding.inflate(inflater,parent,false);return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        vm=ContactsViewModel.of(this);
        adapter=new ContactRowsAdapter((action,id,user)->ContactsUi.act(this,vm,action,id,user));
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.list.addItemDecoration(new RowDividerDecoration(requireContext()));
        ContactsUi.bindChrome(this,vm,binding.toolbar,binding.swipe,vm::refresh);
        vm.blocked.observe(getViewLifecycleOwner(),value->render());
        vm.busy.observe(getViewLifecycleOwner(),value->render());
        vm.operation.observe(getViewLifecycleOwner(),value->render());
        vm.needsRefresh.observe(getViewLifecycleOwner(),value->render());
        vm.sessionActive.observe(getViewLifecycleOwner(),value->render());
        vm.listError.observe(getViewLifecycleOwner(),value->render());
        render();vm.ensureLoaded();
    }

    private void render() {
        if(binding==null) return;
        List<ContactsAdapter.Row> rows=new ArrayList<>();
        List<UserEntity> source=vm.blocked.getValue();
        if(source!=null) for(UserEntity u:source) {
            String name=u.getDisplayName()==null||u.getDisplayName().isBlank()?getString(R.string.contacts_unknown_name):u.getDisplayName();
            User user=new User();user.id=u.getId();user.displayName=name;user.avatarUrl=u.getAvatarUrl();
            rows.add(new ContactsAdapter.Row(u.getId(),name,ContactsText.maskPhone(u.getPhone()),u.getId(),user,List.of("unblock")));
        }
        rows.sort(Comparator.comparing(ContactsAdapter.Row::name,String.CASE_INSENSITIVE_ORDER).thenComparing(ContactsAdapter.Row::key));
        adapter.submitList(rows);adapter.setActionsEnabled(vm.canAct());
        binding.card.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);
        binding.hint.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);

        boolean loading=Boolean.TRUE.equals(vm.busy.getValue()),active=Boolean.TRUE.equals(vm.sessionActive.getValue());
        binding.progress.setVisibility(loading&&!"refresh".equals(vm.operation.getValue())?View.VISIBLE:View.GONE);
        binding.swipe.setEnabled(active&&!loading);
        binding.swipe.setRefreshing(loading&&"refresh".equals(vm.operation.getValue()));
        var error=vm.listError.getValue();
        boolean status=!active||error!=null||rows.isEmpty()&&!loading;
        binding.empty.setVisibility(status?View.VISIBLE:View.GONE);
        binding.empty.setText(!active?getString(R.string.contacts_session_changed):error!=null?ContactsUi.message(this,error)
                :getString(R.string.contacts_blocked_empty));
    }

    @Override public void onDestroyView() {
        binding.list.setAdapter(null);adapter=null;binding=null;super.onDestroyView();
    }
}
