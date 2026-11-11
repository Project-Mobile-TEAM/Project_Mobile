package com.utechat.ui.chat;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.utechat.databinding.FragmentChatListBinding;

/** Fragment khung chat; không triển khai nghiệp vụ thuộc các phase sau. */
public class ChatListFragment extends Fragment {
    private FragmentChatListBinding binding; // Binding chỉ sống trong vòng đời View của Fragment.

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentChatListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Bỏ tham chiếu View cũ để không leak sau xoay màn hình.
    }
}
