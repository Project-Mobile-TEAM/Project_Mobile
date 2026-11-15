package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.utechat.R;
import com.utechat.databinding.FragmentLanguageBinding;

/** Chọn ngôn ngữ Việt/Anh (ADR-021); AppCompat tự tạo lại Activity và ghi nhớ lựa chọn. */
public class LanguageFragment extends Fragment {
    private FragmentLanguageBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLanguageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        String current = AppSettings.getLanguage();
        SettingRow.base(binding.rowVi, 0, getString(R.string.profile_language_vi));
        SettingRow.radio(binding.rowVi, "vi".equals(current), v -> choose("vi"));
        SettingRow.base(binding.rowEn, 0, getString(R.string.profile_language_en));
        SettingRow.radio(binding.rowEn, "en".equals(current), v -> choose("en"));
    }

    private void choose(String tag) {
        if (tag.equals(AppSettings.getLanguage())) return;
        binding.rowVi.radio.setChecked("vi".equals(tag));
        binding.rowEn.radio.setChecked("en".equals(tag));
        AppSettings.setLanguage(tag);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
