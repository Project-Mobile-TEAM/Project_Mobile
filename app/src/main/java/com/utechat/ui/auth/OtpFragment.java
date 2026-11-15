package com.utechat.ui.auth;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.databinding.FragmentOtpBinding;

/** Đăng ký bước 2: nhập OTP 6 số, có đếm ngược 60 giây để gửi lại (F-01). */
public class OtpFragment extends Fragment {
    private static final int CODE_LENGTH = 6;
    private FragmentOtpBinding binding;
    private AuthViewModel viewModel;
    private TextView[] boxes; // Sáu ô hiển thị chữ số.
    private CountDownTimer timer; // Hủy ở onDestroyView để không rò rỉ.

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentOtpBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        // Sau khi tiến trình bị hệ điều hành thu hồi, ViewModel mất số điện thoại: quay lại bước nhập số.
        if (viewModel.getPhone() == null) {
            Navigation.findNavController(view).navigateUp();
            return;
        }
        binding.tvSubtitle.setText(getString(R.string.auth_otp_subtitle, AuthValidator.displayPhone(viewModel.getPhone())));
        boxes = new TextView[]{binding.otp1, binding.otp2, binding.otp3, binding.otp4, binding.otp5, binding.otp6};
        renderCode("");

        binding.etCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                renderCode(s.toString());
                binding.tvError.setVisibility(View.GONE);
            }
        });
        binding.btnConfirm.setOnClickListener(v -> confirm());
        binding.btnResend.setOnClickListener(v -> resend());
        startCountdown();

        // Hiện OTP demo (chỉ khi server/Fake cung cấp) và mở bàn phím; chỉ làm lần đầu, không lặp khi xoay màn hình.
        if (state == null) {
            if (viewModel.getDemoCode() != null) {
                Snackbar.make(view, getString(R.string.auth_otp_demo_code, viewModel.getDemoCode()), Snackbar.LENGTH_LONG).show();
            }
            binding.etCode.requestFocus();
            binding.etCode.post(() -> {
                if (binding == null) return; // Fragment đã bị hủy trước khi chạy.
                WindowCompat.getInsetsController(requireActivity().getWindow(), binding.etCode)
                        .show(WindowInsetsCompat.Type.ime());
            });
        }
    }

    /** Điền chữ số vào các ô và đánh dấu ô đang chờ nhập; chỉ cho bấm Xác nhận khi đủ 6 số. */
    private void renderCode(String code) {
        for (int i = 0; i < CODE_LENGTH; i++) {
            boxes[i].setText(i < code.length() ? String.valueOf(code.charAt(i)) : "");
            boxes[i].setSelected(i == code.length());
        }
        binding.btnConfirm.setEnabled(code.length() == CODE_LENGTH);
    }

    private void confirm() {
        binding.btnConfirm.setEnabled(false);
        if (viewModel.isLoginFlow()) {
            viewModel.verifyLoginOtp(String.valueOf(binding.etCode.getText())).observe(getViewLifecycleOwner(), result -> {
                binding.btnConfirm.setEnabled(true);
                if (result.isSuccess()) Navigation.findNavController(binding.getRoot()).navigate(R.id.action_otp_to_main);
                else showError(result.errorCode);
            });
            return;
        }
        viewModel.verifyOtp(String.valueOf(binding.etCode.getText())).observe(getViewLifecycleOwner(), result -> {
            binding.btnConfirm.setEnabled(true);
            if (result.isSuccess()) {
                Navigation.findNavController(binding.getRoot()).navigate(viewModel.isResetFlow()
                        ? R.id.action_otp_to_reset : R.id.action_otp_to_complete);
            } else {
                showError(result.errorCode);
            }
        });
    }

    private void resend() {
        binding.btnResend.setEnabled(false);
        viewModel.resendOtp().observe(getViewLifecycleOwner(), result -> {
            if (result.isSuccess()) {
                binding.etCode.setText(""); // Mã cũ không còn dùng được.
                if (viewModel.getDemoCode() != null) {
                    Snackbar.make(binding.getRoot(), getString(R.string.auth_otp_demo_code, viewModel.getDemoCode()),
                            Snackbar.LENGTH_LONG).show();
                }
                startCountdown();
            } else {
                showError(result.errorCode);
                startCountdown(); // Khôi phục trạng thái nút theo thời gian còn lại.
            }
        });
    }

    private void showError(String code) {
        binding.tvError.setText(AuthErrors.message(code));
        binding.tvError.setVisibility(View.VISIBLE);
    }

    /** Đếm ngược theo mốc lưu trong ViewModel nên xoay màn hình không làm đếm lại từ đầu. */
    private void startCountdown() {
        if (timer != null) timer.cancel();
        long remaining = viewModel.resendRemainingMillis();
        if (remaining <= 0) {
            showResendReady();
            return;
        }
        binding.btnResend.setEnabled(false);
        timer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long millisLeft) {
                // Làm tròn lên để hiện 00:60 thay vì nhảy thẳng sang 00:59 ngay tick đầu.
                long seconds = (millisLeft + 999) / 1000;
                binding.btnResend.setText(getString(R.string.auth_otp_resend_in, DateUtils.formatElapsedTime(seconds)));
            }
            @Override public void onFinish() { showResendReady(); }
        }.start();
    }

    private void showResendReady() {
        binding.btnResend.setText(R.string.auth_otp_resend);
        binding.btnResend.setEnabled(true);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (timer != null) timer.cancel();
        binding = null;
    }
}
