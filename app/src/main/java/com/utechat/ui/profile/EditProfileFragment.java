package com.utechat.ui.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.repository.UserProfile;
import com.utechat.databinding.FragmentEditProfileBinding;
import com.utechat.ui.auth.AuthErrors;
import com.utechat.ui.auth.AuthValidator;

/** Sửa hồ sơ: chỉ đổi được tên hiển thị (F-08); số điện thoại không đổi được. */
public class EditProfileFragment extends Fragment {
    private FragmentEditProfileBinding binding;
    private ProfileViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentEditProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, ProfileViewModel.FACTORY).get(ProfileViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.toolbar.setOnMenuItemClickListener(item -> { save(); return true; });
        // Avatar hiển thị chữ cái đầu của tên đang gõ.
        binding.etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { binding.tvAvatar.setText(AuthValidator.initials(s.toString())); }
        });
        viewModel.getProfile().observe(getViewLifecycleOwner(), p -> {
            if (p == null) return;
            binding.etPhone.setText(AuthValidator.displayPhone(p.phone));
            // Chỉ điền tên lần đầu; sau xoay màn hình EditText tự khôi phục nội dung người dùng đang gõ.
            if (state == null && binding.etName.length() == 0) binding.etName.setText(p.displayName);
        });
    }

    private void save() {
        UserProfile p = viewModel.getProfile().getValue();
        String name = String.valueOf(binding.etName.getText()).trim();
        if (!AuthValidator.isNameValid(name)) {
            binding.tilName.setError(getString(R.string.auth_error_name_invalid));
            return;
        }
        binding.tilName.setError(null);
        if (p == null) return;
        viewModel.updateProfile(name, p.allowMessagesFromNonContacts, p.discoverable)
                .observe(getViewLifecycleOwner(), result -> {
                    if (result.isSuccess()) {
                        Navigation.findNavController(binding.getRoot()).navigateUp();
                    } else {
                        Snackbar.make(binding.getRoot(), AuthErrors.message(result.errorCode), Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
