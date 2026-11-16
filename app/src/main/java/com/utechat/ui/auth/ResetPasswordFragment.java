package com.utechat.ui.auth;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.databinding.FragmentResetPasswordBinding;

/** Đặt lại mật khẩu (F-04 bước 3): hiện độ mạnh và tiêu chí khi gõ; thành công thì đăng nhập luôn. */
public class ResetPasswordFragment extends Fragment {
    private FragmentResetPasswordBinding binding;
    private AuthViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentResetPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = AuthViewModel.of(this);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        // Mất resetToken (tiến trình bị thu hồi) thì phải xác minh OTP lại: quay về bước trước.
        if (viewModel.getFlowToken() == null) {
            Navigation.findNavController(view).navigateUp();
            return;
        }
        binding.etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { renderStrength(s.toString()); }
        });
        renderStrength(String.valueOf(binding.etPassword.getText()));
        binding.btnReset.setOnClickListener(v -> submit());
    }

    /** Vẽ lại thanh độ mạnh, nhãn và ba dòng tiêu chí theo mật khẩu đang gõ. */
    private void renderStrength(String password) {
        int score = AuthValidator.passwordScore(password);
        int color = score == 3 ? color(R.color.ute_success) : score == 2 ? color(R.color.ute_warning) : color(R.color.ute_error);
        View[] bars = {binding.bar1, binding.bar2, binding.bar3};
        for (int i = 0; i < bars.length; i++) {
            // Đoạn nào nằm trong số tiêu chí đạt thì sáng màu theo mức, còn lại xám.
            bars[i].setBackgroundTintList(ColorStateList.valueOf(i < score ? color : color(R.color.ute_bar_off)));
        }
        int[] names = {R.string.auth_strength_none, R.string.auth_strength_weak,
                R.string.auth_strength_medium, R.string.auth_strength_strong};
        binding.tvStrength.setText(getString(R.string.auth_strength, getString(names[score])));
        binding.tvStrength.setTextColor(score == 0 ? color(R.color.ute_text_secondary) : color);

        setRule(binding.tvRuleLength, password.length() >= 8);
        setRule(binding.tvRuleLetters, password.matches(".*[A-Za-z].*") && password.matches(".*\\d.*"));
        setRule(binding.tvRuleSpecial, password.matches(".*[^A-Za-z0-9].*"));
    }

    /** Đổi icon, màu chữ và mô tả trạng thái (cho TalkBack) của một tiêu chí. */
    private void setRule(TextView view, boolean met) {
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(met ? R.drawable.ic_check : R.drawable.ic_close, 0, 0, 0);
        view.setTextColor(color(met ? R.color.ute_text : R.color.ute_text_secondary));
        ViewCompat.setStateDescription(view, getString(met ? R.string.auth_rule_met : R.string.auth_rule_unmet));
    }

    private int color(@ColorRes int id) { return ContextCompat.getColor(requireContext(), id); }

    private void submit() {
        String password = String.valueOf(binding.etPassword.getText()); // Không cắt khoảng trắng, không ghi log.
        String confirm = String.valueOf(binding.etConfirm.getText());
        boolean passwordOk = AuthValidator.isPasswordValid(password);
        boolean matches = password.equals(confirm);
        binding.tilPassword.setError(passwordOk ? null : getString(R.string.auth_error_password_weak));
        binding.tilConfirm.setError(matches ? null : getString(R.string.auth_error_password_mismatch));
        if (!passwordOk || !matches) return;

        binding.btnReset.setEnabled(false);
        viewModel.completePasswordReset(password).observe(getViewLifecycleOwner(), result -> {
            binding.btnReset.setEnabled(true);
            if (result.isSuccess()) {
                Navigation.findNavController(binding.getRoot()).navigate(R.id.action_reset_to_main);
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
