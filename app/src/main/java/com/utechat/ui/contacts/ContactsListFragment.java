package com.utechat.ui.contacts;

import android.os.Bundle;
import android.text.TextWatcher;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.utechat.R;
import com.utechat.databinding.FragmentContactsListBinding;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.model.ContactDtos.User;
import com.utechat.ui.chat.ChatNavigation;
import java.util.*;

/** Mục 16: chỉ danh sách bạn bè; giữ ID/title cũ và binding đúng vòng đời View. */
public class ContactsListFragment extends Fragment {
    private FragmentContactsListBinding binding;
    private ContactsViewModel vm;
    private ContactsAdapter adapter;
    private TextWatcher filterWatcher;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state) {
        binding=FragmentContactsListBinding.inflate(inflater,parent,false);return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        vm=ContactsViewModel.of(this);
        // Màn chính chỉ dựng danh sách bạn; trạng thái thuộc graph nên Back/recreate dùng lại ViewModel.
        binding.toolbar.setTitle(R.string.title_contacts);
        binding.tabs.setVisibility(View.GONE);
        adapter=new ContactsAdapter((action,id,user)->ContactsUi.act(this,vm,action,id,user),
                this::openChat);
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        binding.find.setOnClickListener(v->navigate(R.id.action_contacts_to_search));
        binding.requests.setOnClickListener(v->navigate(R.id.action_contacts_to_requests));
        binding.blocked.setOnClickListener(v->navigate(R.id.action_contacts_to_blocked));
        binding.refresh.setOnClickListener(v->vm.refresh());
        binding.swipe.setOnRefreshListener(vm::refresh);
        binding.swipe.setColorSchemeColors(com.google.android.material.color.MaterialColors.getColor(view,androidx.appcompat.R.attr.colorPrimary));
        binding.swipe.setProgressBackgroundColorSchemeColor(com.google.android.material.color.MaterialColors.getColor(view,com.google.android.material.R.attr.colorSurface));
        binding.filter.setText(vm.filter());
        filterWatcher=ContactsUi.watcher(value->vm.state.set("filter",value));
        binding.filter.addTextChangedListener(filterWatcher);
        // Observer gắn viewLifecycleOwner để View đã hủy không còn nhận cache hoặc Snackbar.
        vm.state.<String>getLiveData("filter","").observe(getViewLifecycleOwner(),value->{
            if(!binding.filter.getText().toString().equals(value)) binding.filter.setText(value);
            render();
        });
        vm.friends.observe(getViewLifecycleOwner(),value->render());
        vm.count.observe(getViewLifecycleOwner(),value->binding.requests.setText(value==null||value<=0
                ?getString(R.string.contacts_requests):getString(R.string.contacts_count,value)));
        vm.busy.observe(getViewLifecycleOwner(),value->render());
        vm.operation.observe(getViewLifecycleOwner(),value->render());
        vm.needsRefresh.observe(getViewLifecycleOwner(),value->render());
        vm.sessionActive.observe(getViewLifecycleOwner(),value->render());
        vm.listError.observe(getViewLifecycleOwner(),value->render());
        vm.notice.observe(getViewLifecycleOwner(),value->{
            if(value!=null) {
                var event=vm.consumeNotice();
                if(event!=null) ContactsUi.notice(this,binding.getRoot(),event,vm::refresh);
            }
        });
        render();vm.ensureLoaded();
    }
    private void openChat(String peerId) {
        if(!Boolean.TRUE.equals(vm.sessionActive.getValue())) return;
        var nav=NavHostFragment.findNavController(this);
        var current=nav.getCurrentDestination();
        if(current!=null&&current.getId()==R.id.contactsListFragment) ChatNavigation.openDirectChat(nav,peerId);
    }
    private void navigate(int action) {
        if(Boolean.TRUE.equals(vm.sessionActive.getValue())&&!Boolean.TRUE.equals(vm.busy.getValue()))
            NavHostFragment.findNavController(this).navigate(action);
    }
    /** Chỉ lọc danh sách hiển thị; không sửa cache và không suy quan hệ từ tên/hồ sơ. */
    private void render() {
        if(binding==null) return;
        List<ContactsAdapter.Row> rows=new ArrayList<>();
        List<UserEntity> source=vm.friends.getValue();
        String filter=vm.filter().trim().toLowerCase(Locale.ROOT);
        if(source!=null) for(UserEntity u:source) {
            String name=u.getDisplayName()==null?getString(R.string.contacts_unknown_name):u.getDisplayName();
            if(!name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            User user=new User();user.id=u.getId();user.displayName=name;user.avatarUrl=u.getAvatarUrl();
            rows.add(new ContactsAdapter.Row(u.getId(),name,u.getPhone(),u.getId(),user,List.of("unfriend","block")));
        }
        rows.sort(Comparator.comparing(ContactsAdapter.Row::name,String.CASE_INSENSITIVE_ORDER).thenComparing(ContactsAdapter.Row::key));
        adapter.submitList(rows);adapter.setActionsEnabled(vm.canAct());
        // Chặn click lặp khi đang xử lý; sau lỗi ghi chưa rõ chỉ cho tải lại để đối soát.
        boolean loading=Boolean.TRUE.equals(vm.busy.getValue()),active=Boolean.TRUE.equals(vm.sessionActive.getValue());
        binding.progress.setVisibility(loading?View.VISIBLE:View.GONE);
        binding.swipe.setEnabled(active&&!loading);
        binding.swipe.setRefreshing(loading&&"refresh".equals(vm.operation.getValue()));
        binding.refresh.setEnabled(active&&!loading);
        binding.find.setEnabled(active&&!loading);binding.requests.setEnabled(active&&!loading);binding.blocked.setEnabled(active&&!loading);
        binding.filter.setEnabled(active);
        var error=vm.listError.getValue();
        binding.refresh.setText(error==null?R.string.contacts_refresh:R.string.contacts_retry);
        boolean status=error!=null||!active||rows.isEmpty()&&!loading;
        binding.empty.setVisibility(status?View.VISIBLE:View.GONE);
        binding.empty.setText(!active?getString(R.string.contacts_session_changed):error!=null?ContactsUi.message(this,error)
                :getString(filter.isEmpty()?R.string.contacts_empty:R.string.contacts_no_match));
    }
    @Override public void onDestroyView() {
        binding.filter.removeTextChangedListener(filterWatcher);binding.list.setAdapter(null);
        filterWatcher=null;adapter=null;binding=null;super.onDestroyView();
    }
}
