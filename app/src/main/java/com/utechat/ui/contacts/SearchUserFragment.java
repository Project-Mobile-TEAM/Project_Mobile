package com.utechat.ui.contacts;

import android.os.Bundle;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.utechat.R;
import com.utechat.data.remote.model.ContactDtos.Lookup;
import com.utechat.data.repository.RelationshipState;
import com.utechat.databinding.FragmentSearchUserBinding;
import com.utechat.ui.auth.PhoneField;
import java.util.List;

/** Mục 17: ô SĐT +84 → ViewModel.search() → thẻ kết quả theo 5 trạng thái quan hệ hoặc trạng thái "Không tìm thấy". */
public class SearchUserFragment extends Fragment {
    private FragmentSearchUserBinding binding;
    private ContactsViewModel vm;
    private ContactRowsAdapter adapter;
    private TextWatcher watcher;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state) {
        binding=FragmentSearchUserBinding.inflate(inflater,parent,false);return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        vm=ContactsViewModel.of(this);
        adapter=new ContactRowsAdapter((action,id,user)->ContactsUi.act(this,vm,action,id,user));
        binding.result.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.result.setAdapter(adapter);
        PhoneField.attach(binding.phoneLayout,binding.phone);
        binding.phone.setText(ContactsText.typedPhone(vm.phone()));
        // Số lưu trong SavedStateHandle ở dạng E.164 nên ViewModel.search() dùng thẳng được và sống qua xoay màn hình.
        watcher=ContactsUi.watcher(value->{
            String stored=ContactsText.storedPhone(value);
            if(!stored.equals(vm.phone())) vm.state.set("phone",stored);
            vm.error.setValue(null);
        });
        binding.phone.addTextChangedListener(watcher);
        binding.phone.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH) return false;
            vm.search();return true;
        });
        binding.find.setOnClickListener(v->vm.search());
        ContactsUi.bindChrome(this,vm,binding.toolbar,null,vm::search);
        vm.lookup.observe(getViewLifecycleOwner(),value->render());
        vm.error.observe(getViewLifecycleOwner(),value->render());
        vm.busy.observe(getViewLifecycleOwner(),value->render());
        vm.sessionActive.observe(getViewLifecycleOwner(),value->render());
        vm.needsRefresh.observe(getViewLifecycleOwner(),value->render());
        render();vm.ensureLoaded();
    }

    private void render() {
        if(binding==null) return;
        boolean loading=Boolean.TRUE.equals(vm.busy.getValue()),active=Boolean.TRUE.equals(vm.sessionActive.getValue());
        Lookup found=vm.lookup.getValue();
        String code=vm.error.getValue()==null?"":String.valueOf(vm.error.getValue().errorCode);
        binding.progress.setVisibility(loading?View.VISIBLE:View.GONE);
        binding.find.setEnabled(active&&!loading);
        binding.phoneLayout.setEnabled(active);
        binding.phoneLayout.setError("PHONE_INVALID".equals(code)?getString(R.string.contacts_invalid):null);
        binding.notFound.setVisibility(found==null&&!loading&&"NOT_FOUND".equals(code)?View.VISIBLE:View.GONE);
        binding.resultGroup.setVisibility(found==null?View.GONE:View.VISIBLE);
        adapter.submitList(found==null?List.of():List.of(row(found)));
        adapter.setActionsEnabled(vm.canAct());
    }

    /** Nút theo RelationshipState; lời mời chờ dùng ID lời mời, các trạng thái khác dùng ID người dùng. */
    private ContactsAdapter.Row row(Lookup found) {
        RelationshipState state=null;
        try { state=RelationshipState.valueOf(found.relationshipStatus); }
        catch(IllegalArgumentException|NullPointerException invalid) { /* Trạng thái lạ: hiện hồ sơ, không có nút. */ }
        boolean pending=state==RelationshipState.INCOMING_PENDING||state==RelationshipState.OUTGOING_PENDING;
        String name=found.displayName==null||found.displayName.isBlank()?getString(R.string.contacts_unknown_name):found.displayName;
        // Lookup không có SĐT; dùng số vừa nhập đã chuẩn hóa để hiển thị.
        String phone=ContactsText.formatPhone(ContactsValidator.normalize(vm.phone()));
        String label=state==null||state==RelationshipState.STRANGER?null:getString(switch(state) {
            case OUTGOING_PENDING -> R.string.contacts_pending_out;
            case INCOMING_PENDING -> R.string.contacts_pending_in;
            case FRIEND -> R.string.contacts_friend;
            default -> R.string.contacts_blocked_state;
        });
        return new ContactsAdapter.Row(found.id,name,label==null?phone:phone+" · "+label,pending?found.pendingRequestId:found.id,
                found,state==null?List.of():state.actions());
    }

    @Override public void onDestroyView() {
        binding.phone.removeTextChangedListener(watcher);binding.result.setAdapter(null);
        watcher=null;adapter=null;binding=null;super.onDestroyView();
    }
}
