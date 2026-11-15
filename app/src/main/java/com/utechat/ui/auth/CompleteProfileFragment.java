package com.utechat.ui.auth;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.databinding.FragmentCompleteProfileBinding;

/** Đăng ký bước 3: đặt tên hiển thị và mật khẩu để tạo tài khoản (F-01). */
public class CompleteProfileFragment extends Fragment {
    private FragmentCompleteProfileBinding binding;
    private AuthViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCompleteProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        // Mất registrationToken (tiến trình bị thu hồi) thì phải xác minh OTP lại: quay về bước trước.
        if (viewModel.getFlowToken() == null) {
            Navigation.findNavController(view).navigateUp();
            return;
        }
        // Avatar hiển thị chữ cái đầu của tên đang gõ.
        binding.etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                binding.tvAvatar.setText(AuthValidator.initials(s.toString()));
            }
        });
        binding.btnCreate.setOnClickListener(v -> submit());
    }

    private void submit() {
        String name = String.valueOf(binding.etName.getText()).trim();
        String password = String.valueOf(binding.etPassword.getText()); // Không cắt khoảng trắng, không ghi log.
        String confirm = String.valueOf(binding.etConfirm.getText());
        boolean nameOk = AuthValidator.isNameValid(name);
        boolean passwordOk = AuthValidator.isPasswordValid(password);
        boolean matches = password.equals(confirm);
        binding.tilName.setError(nameOk ? null : getString(R.string.auth_error_name_invalid));
        binding.tilPassword.setError(passwordOk ? null : getString(R.string.auth_error_password_weak));
        binding.tilConfirm.setError(matches ? null : getString(R.string.auth_error_password_mismatch));
        if (!nameOk || !passwordOk || !matches) return;

        binding.btnCreate.setEnabled(false);
        viewModel.completeRegister(name, password).observe(getViewLifecycleOwner(), result -> {
            binding.btnCreate.setEnabled(true);
            if (result.isSuccess()) {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_complete_to_main);
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
