package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.repository.UserProfile;
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

        binding.cardMe.setOnClickListener(v -> go(R.id.action_profile_to_edit));
        binding.btnLogout.setOnClickListener(v -> logout());
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

    /** Đăng xuất (F-06): xoá phiên rồi về màn Đăng nhập, bỏ toàn bộ back stack của app. */
    private void logout() {
        viewModel.logout();
        Navigation.findNavController(binding.getRoot()).navigate(R.id.nav_auth, null,
                new NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
