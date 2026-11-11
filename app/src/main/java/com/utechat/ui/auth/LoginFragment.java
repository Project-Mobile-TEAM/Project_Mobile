package com.utechat.ui.auth;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.utechat.BuildConfig;
import com.utechat.R;
import com.utechat.databinding.FragmentLoginBinding;

/** Fragment khung auth; không triển khai nghiệp vụ thuộc các phase sau. */
public class LoginFragment extends Fragment {
    private FragmentLoginBinding binding; // Binding chỉ sống trong vòng đời View của Fragment.

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        // Release không được mở đường tắt bỏ qua xác thực.
        binding.btnPreview.setVisibility(BuildConfig.DEBUG && BuildConfig.USE_FAKE_DATA ? View.VISIBLE : View.GONE);
        binding.btnPreview.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_login_to_main));
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
