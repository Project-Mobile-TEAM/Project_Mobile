package com.utechat.ui.auth;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.data.repository.fake.FakeAuthRepository;
import com.utechat.databinding.FragmentLoginBinding;

/** Màn đăng nhập bằng số điện thoại + mật khẩu (F-02). */
public class LoginFragment extends Fragment {
    private FragmentLoginBinding binding; // Binding chỉ sống trong vòng đời View của Fragment.
    private AuthViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.btnLogin.setOnClickListener(v -> submit());
        binding.btnGoRegister.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_login_to_register));
        binding.btnForgot.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_login_to_forgot));
        // Release không được mở đường tắt bỏ qua xác thực.
        binding.btnPreview.setVisibility(BuildConfig.DEBUG && BuildConfig.USE_FAKE_DATA ? View.VISIBLE : View.GONE);
        binding.btnPreview.setOnClickListener(v -> {
            // Đăng nhập tài khoản demo (Alice) để các tab sau có hồ sơ; chỉ chạy được khi Fake bật (Debug).
            viewModel.login("+84901111111", FakeAuthRepository.DEMO_PASSWORD);
            Navigation.findNavController(v).navigate(R.id.action_login_to_main);
        });
    }

    private void submit() {
        // Chuẩn hoá về E.164 trước khi gửi; mật khẩu giữ nguyên, không cắt khoảng trắng và không ghi log.
        String phone = AuthValidator.toE164(String.valueOf(binding.etPhone.getText()));
        String password = String.valueOf(binding.etPassword.getText());
        binding.tilPhone.setError(phone == null ? getString(R.string.auth_error_phone_invalid) : null);
        binding.tilPassword.setError(password.isEmpty() ? getString(R.string.auth_error_password_required) : null);
        if (phone == null || password.isEmpty()) return;

        binding.btnLogin.setEnabled(false); // Chặn bấm lặp trong lúc chờ.
        // shortcut: LiveData theo từng lần gọi; khi Real gọi mạng thật cần giữ kết quả trong ViewModel để sống qua xoay màn hình.
        viewModel.login(phone, password).observe(getViewLifecycleOwner(), result -> {
            binding.btnLogin.setEnabled(true);
            if (!result.isSuccess()) {
                // Sai thông tin hiện lỗi chung (không nói số có tồn tại hay không); bị khoá hay mất mạng thì báo đúng.
                binding.tilPassword.setError(getString(AuthErrors.message(result.errorCode)));
            } else {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_login_to_main);
            }
        });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
