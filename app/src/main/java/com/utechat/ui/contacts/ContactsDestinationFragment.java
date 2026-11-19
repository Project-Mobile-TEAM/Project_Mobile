package com.utechat.ui.contacts;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.databinding.FragmentContactsDestinationBinding;

/** Chỉ giữ đích/back stack của 16; màn nghiệp vụ 17–19 chưa được đưa vào đợt này. */
public class ContactsDestinationFragment extends Fragment {
    private FragmentContactsDestinationBinding binding;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state) {
        binding=FragmentContactsDestinationBinding.inflate(inflater,parent,false);return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        var controller=NavHostFragment.findNavController(this);
        binding.toolbar.setTitle(controller.getCurrentDestination().getLabel());
        binding.toolbar.setNavigationOnClickListener(v->controller.navigateUp());
    }
    @Override public void onDestroyView() { binding=null;super.onDestroyView(); }
}
