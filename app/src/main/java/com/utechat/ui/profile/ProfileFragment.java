package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.repository.UserProfile;
import com.utechat.databinding.DialogDeleteAccountBinding;
import com.utechat.databinding.FragmentProfileBinding;
import com.utechat.ui.auth.AuthErrors;
import com.utechat.ui.auth.AuthValidator;

/** Tab Hồ sơ: thông tin cá nhân, quyền riêng tư, cài đặt ứng dụng, bảo mật và đăng xuất (F-05, F-06, F-08). */
public class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding; // Binding chỉ sống trong vòng đời View của Fragment.
    private ProfileViewModel viewModel;
    private boolean rendering; // true khi đang gán công tắc bằng code, để không tính là người dùng bấm.

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, ProfileViewModel.FACTORY).get(ProfileViewModel.class);

        SettingRow.base(binding.rowStrangers, R.drawable.ic_people, getString(R.string.profile_allow_strangers));
        SettingRow.toggle(binding.rowStrangers, false);
        SettingRow.base(binding.rowDiscoverable, R.drawable.ic_search, getString(R.string.profile_discoverable));
        SettingRow.toggle(binding.rowDiscoverable, false);
        binding.rowStrangers.toggle.setOnCheckedChangeListener((b, checked) -> savePrivacy());
        binding.rowDiscoverable.toggle.setOnCheckedChangeListener((b, checked) -> savePrivacy());

        SettingRow.base(binding.rowLanguage, R.drawable.ic_globe, getString(R.string.profile_language));
        SettingRow.chevron(binding.rowLanguage, getString("vi".equals(AppSettings.getLanguage())
                ? R.string.profile_language_vi : R.string.profile_language_en), v -> go(R.id.action_profile_to_language));
        SettingRow.base(binding.rowTheme, R.drawable.ic_moon, getString(R.string.profile_theme));
        SettingRow.chevron(binding.rowTheme, getString(themeName(AppSettings.getTheme(requireContext()))),
                v -> go(R.id.action_profile_to_theme));
        SettingRow.base(binding.rowNotifications, R.drawable.ic_bell, getString(R.string.profile_notifications));
        SettingRow.toggle(binding.rowNotifications, AppSettings.isNotificationsEnabled(requireContext()));
        binding.rowNotifications.toggle.setOnCheckedChangeListener((b, checked) ->
                AppSettings.setNotificationsEnabled(requireContext(), checked));

        SettingRow.base(binding.rowPassword, R.drawable.ic_lock, getString(R.string.profile_password_title));
        SettingRow.chevron(binding.rowPassword, null, v -> go(R.id.action_profile_to_password));
        SettingRow.base(binding.rowDevices, R.drawable.ic_phone, getString(R.string.profile_devices_title));
        SettingRow.chevron(binding.rowDevices, null, v -> go(R.id.action_profile_to_devices));

        for (com.utechat.databinding.ItemSettingRowBinding row : new com.utechat.databinding.ItemSettingRowBinding[]{
                binding.rowStrangers, binding.rowDiscoverable, binding.rowLanguage, binding.rowTheme,
                binding.rowNotifications, binding.rowPassword, binding.rowDevices}) {
            SettingRow.compact(row);
        }
        binding.cardMe.setOnClickListener(v -> go(R.id.action_profile_to_edit));
        binding.btnLogout.setOnClickListener(v -> logout());
        binding.btnDeleteAccount.setOnClickListener(v -> confirmDelete());
        viewModel.getProfile().observe(getViewLifecycleOwner(), this::render);
    }

    private void go(int action) { Navigation.findNavController(binding.getRoot()).navigate(action); }

    @StringRes private static int themeName(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) return R.string.profile_theme_light;
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) return R.string.profile_theme_dark;
        return R.string.profile_theme_system;
    }

    /** Vẽ thông tin hồ sơ và đặt công tắc theo dữ liệu mới nhất từ repository. */
    private void render(@Nullable UserProfile p) {
        if (p == null) return;
        binding.tvAvatar.setText(AuthValidator.initials(p.displayName));
        binding.tvName.setText(p.displayName);
        binding.tvPhone.setText(AuthValidator.displayPhone(p.phone));
        rendering = true;
        binding.rowStrangers.toggle.setChecked(p.allowMessagesFromNonContacts);
        binding.rowDiscoverable.toggle.setChecked(p.discoverable);
        rendering = false;
    }

    /** Gửi cờ riêng tư lên server ngay khi bấm công tắc; lỗi thì trả công tắc về giá trị cũ. */
    private void savePrivacy() {
        UserProfile p = viewModel.getProfile().getValue();
        if (rendering || p == null) return;
        viewModel.updateProfile(p.displayName, binding.rowStrangers.toggle.isChecked(),
                binding.rowDiscoverable.toggle.isChecked()).observe(getViewLifecycleOwner(), result -> {
            if (!result.isSuccess()) {
                render(viewModel.getProfile().getValue());
                Snackbar.make(binding.getRoot(), AuthErrors.message(result.errorCode), Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /** Xoá tài khoản (F-08): hỏi lại mật khẩu; sai mật khẩu thì báo lỗi ngay trong hộp thoại, không đóng nó. */
    private void confirmDelete() {
        DialogDeleteAccountBinding form = DialogDeleteAccountBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_UTEChat_Dialog_Destructive)
                .setTitle(R.string.profile_delete_title).setMessage(R.string.profile_delete_message)
                .setView(form.getRoot()).setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.profile_delete_confirm, null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = String.valueOf(form.etPassword.getText());
            if (password.isEmpty()) { form.tilPassword.setError(getString(R.string.auth_error_password_required)); return; }
            v.setEnabled(false); // Chặn bấm hai lần khi đang gửi.
            viewModel.deleteAccount(password).observe(getViewLifecycleOwner(), result -> {
                v.setEnabled(true);
                if (result.isSuccess()) { dialog.dismiss(); goToLogin(); }
                else form.tilPassword.setError(getString("BAD_CREDENTIALS".equals(result.errorCode)
                        ? R.string.profile_error_current_password : AuthErrors.message(result.errorCode)));
            });
        });
    }

    /** Đăng xuất (F-06): xoá phiên rồi về màn Đăng nhập, bỏ toàn bộ back stack của app. */
    private void logout() {
        viewModel.logout();
        goToLogin();
    }

    private void goToLogin() {
        Navigation.findNavController(binding.getRoot()).navigate(R.id.nav_auth, null,
                new NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
