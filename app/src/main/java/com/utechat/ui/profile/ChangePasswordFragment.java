package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.databinding.FragmentChangePasswordBinding;
import com.utechat.ui.auth.AuthErrors;
import com.utechat.ui.auth.AuthValidator;

/** Đổi mật khẩu (F-08): thành công thì server đăng xuất các thiết bị khác. */
public class ChangePasswordFragment extends Fragment {
    private FragmentChangePasswordBinding binding;
    private ProfileViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentChangePasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, ProfileViewModel.FACTORY).get(ProfileViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.btnUpdate.setOnClickListener(v -> submit());
    }

    private void submit() {
        // Mật khẩu giữ nguyên (không cắt khoảng trắng) và không bao giờ ghi log.
        String oldPassword = String.valueOf(binding.etOld.getText());
        String newPassword = String.valueOf(binding.etNew.getText());
        String confirm = String.valueOf(binding.etConfirm.getText());
        boolean oldOk = !oldPassword.isEmpty();
        boolean newOk = AuthValidator.isPasswordValid(newPassword);
        boolean matches = newPassword.equals(confirm);
        binding.tilOld.setError(oldOk ? null : getString(R.string.auth_error_password_required));
        binding.tilNew.setError(newOk ? null : getString(R.string.auth_error_password_weak));
        binding.tilConfirm.setError(matches ? null : getString(R.string.auth_error_password_mismatch));
        if (!oldOk || !newOk || !matches) return;

        binding.btnUpdate.setEnabled(false);
        viewModel.changePassword(oldPassword, newPassword).observe(getViewLifecycleOwner(), result -> {
            binding.btnUpdate.setEnabled(true);
            if (result.isSuccess()) {
                // Gắn Snackbar vào Activity để vẫn hiện sau khi màn này đã đóng.
                Snackbar.make(requireActivity().findViewById(android.R.id.content),
                        R.string.profile_password_changed, Snackbar.LENGTH_SHORT).show();
                Navigation.findNavController(binding.getRoot()).navigateUp();
            } else if ("BAD_CREDENTIALS".equals(result.errorCode)) {
                binding.tilOld.setError(getString(R.string.profile_error_current_password));
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
