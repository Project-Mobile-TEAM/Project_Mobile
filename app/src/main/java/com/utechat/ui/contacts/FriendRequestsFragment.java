package com.utechat.ui.contacts;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.utechat.R;
import com.utechat.data.local.dao.ContactRequestWithUser;
import com.utechat.data.remote.model.ContactDtos.User;
import com.utechat.databinding.FragmentFriendRequestsBinding;
import java.util.*;

/** Mục 18: lời mời "Nhận được" (chấp nhận / xóa) và "Đã gửi" (hủy), đọc từ Room qua ViewModel dùng chung của graph. */
public class FriendRequestsFragment extends Fragment {
    private FragmentFriendRequestsBinding binding;
    private ContactsViewModel vm;
    private ContactRowsAdapter incoming,outgoing;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state) {
        binding=FragmentFriendRequestsBinding.inflate(inflater,parent,false);return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        vm=ContactsViewModel.of(this);
        ContactsAdapter.Action act=(action,id,user)->ContactsUi.act(this,vm,action,id,user);
        incoming=new ContactRowsAdapter(act);outgoing=new ContactRowsAdapter(act);
        setUpList(binding.incomingList,incoming);setUpList(binding.outgoingList,outgoing);
        ContactsUi.bindChrome(this,vm,binding.toolbar,binding.swipe,vm::refresh);
        // Observer gắn viewLifecycleOwner để View đã hủy không còn nhận cache.
        vm.requests.observe(getViewLifecycleOwner(),value->render());
        vm.busy.observe(getViewLifecycleOwner(),value->render());
        vm.operation.observe(getViewLifecycleOwner(),value->render());
        vm.needsRefresh.observe(getViewLifecycleOwner(),value->render());
        vm.sessionActive.observe(getViewLifecycleOwner(),value->render());
        vm.listError.observe(getViewLifecycleOwner(),value->render());
        render();vm.ensureLoaded();
    }

    private void setUpList(RecyclerView list,ContactRowsAdapter adapter) {
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.addItemDecoration(new RowDividerDecoration(requireContext()));
    }

    /** Chỉ lời mời còn PENDING; chiều quyết định theo người dùng hiện tại, mới nhất lên đầu. */
    private void render() {
        if(binding==null) return;
        String me=vm.owner();
        List<ContactRequestWithUser> source=new ArrayList<>(vm.requests.getValue()==null?List.of():vm.requests.getValue());
        source.sort(Comparator.comparingLong((ContactRequestWithUser item)->item.request.getCreatedAt()).reversed());
        List<ContactsAdapter.Row> in=new ArrayList<>(),out=new ArrayList<>();
        for(ContactRequestWithUser item:source) {
            var request=item.request;
            if(!"PENDING".equals(request.getStatus())) continue;
            boolean sent=Objects.equals(request.getRequesterId(),me);
            String name=sent?item.addresseeName:item.requesterName;
            if(name==null||name.isBlank()) name=getString(R.string.contacts_unknown_name);
            User other=new User();other.id=sent?request.getAddresseeId():request.getRequesterId();other.displayName=name;
            long at=request.getCreatedAt();
            String when=at<=0?"":DateUtils.getRelativeTimeSpanString(at,System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString();
            // accept/reject/cancel dùng ID lời mời làm mục tiêu.
            (sent?out:in).add(new ContactsAdapter.Row(request.getId(),name,when,request.getId(),other,
                    sent?List.of("cancel"):List.of("accept","reject")));
        }
        incoming.submitList(in);outgoing.submitList(out);
        boolean enabled=vm.canAct();incoming.setActionsEnabled(enabled);outgoing.setActionsEnabled(enabled);
        binding.incomingGroup.setVisibility(in.isEmpty()?View.GONE:View.VISIBLE);
        binding.outgoingGroup.setVisibility(out.isEmpty()?View.GONE:View.VISIBLE);
        binding.incomingLabel.setText(getString(R.string.contacts_section_received,in.size()));
        binding.outgoingLabel.setText(getString(R.string.contacts_section_sent,out.size()));

        boolean loading=Boolean.TRUE.equals(vm.busy.getValue()),active=Boolean.TRUE.equals(vm.sessionActive.getValue());
        binding.progress.setVisibility(loading&&!"refresh".equals(vm.operation.getValue())?View.VISIBLE:View.GONE);
        binding.swipe.setEnabled(active&&!loading);
        binding.swipe.setRefreshing(loading&&"refresh".equals(vm.operation.getValue()));
        var error=vm.listError.getValue();
        boolean status=!active||error!=null||in.isEmpty()&&out.isEmpty()&&!loading;
        binding.empty.setVisibility(status?View.VISIBLE:View.GONE);
        binding.empty.setText(!active?getString(R.string.contacts_session_changed):error!=null?ContactsUi.message(this,error)
                :getString(R.string.contacts_requests_empty));
    }

    @Override public void onDestroyView() {
        binding.incomingList.setAdapter(null);binding.outgoingList.setAdapter(null);
        incoming=null;outgoing=null;binding=null;super.onDestroyView();
    }
}
