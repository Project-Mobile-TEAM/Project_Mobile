package com.utechat.ui.auth;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.utechat.R;
import com.utechat.databinding.FragmentForgotPasswordBinding;

/** Quên mật khẩu bước 1: nhập số điện thoại để nhận OTP đặt lại mật khẩu (F-04). */
public class ForgotPasswordFragment extends Fragment {
    private FragmentForgotPasswordBinding binding;
    private AuthViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentForgotPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.btnSend.setOnClickListener(v -> submit());
    }

    private void submit() {
        String phone = AuthValidator.toE164(String.valueOf(binding.etPhone.getText()));
        if (phone == null) {
            binding.tilPhone.setError(getString(R.string.auth_error_phone_invalid));
            return;
        }
        binding.tilPhone.setError(null);
        binding.btnSend.setEnabled(false); // Chặn bấm lặp (server giới hạn gửi OTP).
        viewModel.requestResetOtp(phone).observe(getViewLifecycleOwner(), result -> {
            binding.btnSend.setEnabled(true);
            if (result.isSuccess()) {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_forgot_to_otp);
            } else {
                binding.tilPhone.setError(getString(AuthErrors.message(result.errorCode)));
            }
        });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
