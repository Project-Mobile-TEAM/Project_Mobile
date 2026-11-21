package com.utechat.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.utechat.R;

/**
 * shortcut: khung tạm cho các đích trong nav_chat chưa làm (phòng chat 05-08, nhóm 05-09) để điều hướng không crash;
 * thay bằng Fragment thật khi làm các plan đó.
 */
abstract class ChatStubFragment extends Fragment {
    @StringRes abstract int title();

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar toolbar = new MaterialToolbar(requireContext());
        toolbar.setTitle(title());
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.chat_back);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        root.addView(toolbar);
        return root;
    }
}
