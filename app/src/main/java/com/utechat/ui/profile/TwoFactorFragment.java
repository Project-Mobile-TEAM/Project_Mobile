package com.utechat.ui.profile;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.utechat.R;
import com.utechat.databinding.FragmentTwoFactorBinding;
import com.utechat.ui.auth.AuthErrors;

/** Bật/tắt 2FA TOTP (F-05, F-06): mật khẩu, khoá bí mật, nhập thử mã, mã dự phòng. Không có QR: mở otpauth:// hoặc nhập khoá tay. */
public class TwoFactorFragment extends Fragment {
    private FragmentTwoFactorBinding binding;
    private TwoFactorViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentTwoFactorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, TwoFactorViewModel.FACTORY).get(TwoFactorViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.btnRetry.setOnClickListener(v -> load());
        binding.btnSetup.setOnClickListener(v -> setup());
        binding.btnOpenApp.setOnClickListener(v -> openAuthenticator());
        binding.btnEnable.setOnClickListener(v -> enable());
        binding.btnCopyCodes.setOnClickListener(v -> copyCodes());
        binding.btnCodesDone.setOnClickListener(v -> viewModel.codesSaved());
        binding.btnDisable.setOnClickListener(v -> disable());
        viewModel.getStep().observe(getViewLifecycleOwner(), this::render);
        // Chỉ hỏi server khi chưa có trạng thái; xoay màn hình giữa chừng không làm mất bước đang làm.
        TwoFactorViewModel.Step s = viewModel.getStep().getValue();
        if (s == TwoFactorViewModel.Step.LOADING || s == TwoFactorViewModel.Step.ERROR) load();
    }

    private void load() { viewModel.load().observe(getViewLifecycleOwner(), r -> { }); }

    private void render(TwoFactorViewModel.Step step) {
        binding.progress.setVisibility(step == TwoFactorViewModel.Step.LOADING ? View.VISIBLE : View.GONE);
        binding.secError.setVisibility(step == TwoFactorViewModel.Step.ERROR ? View.VISIBLE : View.GONE);
        binding.secOff.setVisibility(step == TwoFactorViewModel.Step.OFF ? View.VISIBLE : View.GONE);
        binding.secSetup.setVisibility(step == TwoFactorViewModel.Step.SETUP ? View.VISIBLE : View.GONE);
        binding.secCodes.setVisibility(step == TwoFactorViewModel.Step.CODES ? View.VISIBLE : View.GONE);
        binding.secOn.setVisibility(step == TwoFactorViewModel.Step.ON ? View.VISIBLE : View.GONE);
        if (step == TwoFactorViewModel.Step.SETUP && viewModel.getSetup() != null) {
            binding.tvSecret.setText(viewModel.getSetup().secret);
            binding.ivQr.setImageBitmap(qr(viewModel.getSetup().otpauthUri));
        } else if (step == TwoFactorViewModel.Step.CODES && viewModel.getBackupCodes() != null) {
            binding.tvCodes.setText(String.join("\n", viewModel.getBackupCodes()));
        } else {
            binding.tvSecret.setText("");
            binding.ivQr.setImageDrawable(null);
            binding.tvCodes.setText("");
            binding.etSetupPassword.setText("");
            binding.etEnableCode.setText("");
            binding.etDisablePassword.setText("");
            binding.etDisableCode.setText("");
        }
    }

    /** Mã QR đen trên nền trắng (ứng dụng xác thực cần tương phản cao, kể cả ở chế độ tối). Lỗi mã hoá thì không hiện QR, người dùng vẫn nhập khoá tay được. */
    @Nullable private static Bitmap qr(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512);
            int[] px = new int[m.getWidth() * m.getHeight()];
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++) px[y * m.getWidth() + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, m.getWidth(), m.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return null;
        }
    }

    private void setup() {
        String password = String.valueOf(binding.etSetupPassword.getText()); // Giữ nguyên, không log.
        binding.tilSetupPassword.setError(password.isEmpty() ? getString(R.string.auth_error_password_required) : null);
        if (password.isEmpty()) return;
        binding.btnSetup.setEnabled(false);
        viewModel.setup(password).observe(getViewLifecycleOwner(), r -> {
            binding.btnSetup.setEnabled(true);
            if (r.isSuccess()) return;
            if ("BAD_CREDENTIALS".equals(r.errorCode)) binding.tilSetupPassword.setError(getString(R.string.profile_error_current_password));
            else if ("TWO_FACTOR_ALREADY_ENABLED".equals(r.errorCode)) load();
            else Snackbar.make(binding.getRoot(), AuthErrors.message(r.errorCode), Snackbar.LENGTH_LONG).show();
        });
    }

    private void openAuthenticator() {
        if (viewModel.getSetup() == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.getSetup().otpauthUri)));
        } catch (ActivityNotFoundException e) {
            Snackbar.make(binding.getRoot(), R.string.profile_two_factor_no_app, Snackbar.LENGTH_LONG).show();
        }
    }

    private void enable() {
        String code = String.valueOf(binding.etEnableCode.getText()).trim();
        binding.tilEnableCode.setError(code.length() == 6 ? null : getString(R.string.auth_error_code_invalid));
        if (code.length() != 6) return;
        binding.btnEnable.setEnabled(false);
        viewModel.enable(code).observe(getViewLifecycleOwner(), r -> {
            binding.btnEnable.setEnabled(true);
            if (!r.isSuccess()) {
                if ("CODE_INVALID".equals(r.errorCode)) binding.tilEnableCode.setError(getString(R.string.auth_error_code_invalid));
                else Snackbar.make(binding.getRoot(), AuthErrors.message(r.errorCode), Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void copyCodes() {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("backup-codes", binding.tvCodes.getText()));
        Snackbar.make(binding.getRoot(), R.string.profile_two_factor_copied, Snackbar.LENGTH_SHORT).show();
    }

    private void disable() {
        String password = String.valueOf(binding.etDisablePassword.getText());
        String code = String.valueOf(binding.etDisableCode.getText()).trim();
        binding.tilDisablePassword.setError(password.isEmpty() ? getString(R.string.auth_error_password_required) : null);
        binding.tilDisableCode.setError(code.isEmpty() ? getString(R.string.auth_error_code_invalid) : null);
        if (password.isEmpty() || code.isEmpty()) return;
        binding.btnDisable.setEnabled(false);
        viewModel.disable(password, code).observe(getViewLifecycleOwner(), r -> {
            binding.btnDisable.setEnabled(true);
            if (r.isSuccess()) {
                Snackbar.make(binding.getRoot(), R.string.profile_two_factor_disabled, Snackbar.LENGTH_SHORT).show();
            } else if ("BAD_CREDENTIALS".equals(r.errorCode)) {
                binding.tilDisablePassword.setError(getString(R.string.profile_error_current_password));
            } else if ("CODE_INVALID".equals(r.errorCode)) {
                binding.tilDisableCode.setError(getString(R.string.auth_error_code_invalid));
            } else {
                Snackbar.make(binding.getRoot(), AuthErrors.message(r.errorCode), Snackbar.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
