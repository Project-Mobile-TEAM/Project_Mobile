package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.utechat.R;
import com.utechat.databinding.FragmentThemeBinding;

/** Chọn giao diện Sáng / Tối / Theo hệ thống (ADR-021). */
public class ThemeFragment extends Fragment {
    private FragmentThemeBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentThemeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        int current = AppSettings.getTheme(requireContext());
        SettingRow.base(binding.rowLight, R.drawable.ic_sun, getString(R.string.profile_theme_light));
        SettingRow.radio(binding.rowLight, current == AppCompatDelegate.MODE_NIGHT_NO, v -> choose(AppCompatDelegate.MODE_NIGHT_NO));
        SettingRow.base(binding.rowDark, R.drawable.ic_moon, getString(R.string.profile_theme_dark));
        SettingRow.radio(binding.rowDark, current == AppCompatDelegate.MODE_NIGHT_YES, v -> choose(AppCompatDelegate.MODE_NIGHT_YES));
        SettingRow.base(binding.rowSystem, R.drawable.ic_phone, getString(R.string.profile_theme_system));
        SettingRow.radio(binding.rowSystem, current == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                v -> choose(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
    }

    private void choose(int mode) {
        binding.rowLight.radio.setChecked(mode == AppCompatDelegate.MODE_NIGHT_NO);
        binding.rowDark.radio.setChecked(mode == AppCompatDelegate.MODE_NIGHT_YES);
        binding.rowSystem.radio.setChecked(mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppSettings.setTheme(requireContext(), mode); // Lưu và áp dụng ngay; Activity tự tạo lại.
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
