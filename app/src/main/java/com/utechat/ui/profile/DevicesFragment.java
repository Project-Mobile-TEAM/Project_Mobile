package com.utechat.ui.profile;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.utechat.R;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.databinding.FragmentDevicesBinding;
import com.utechat.ui.auth.AuthErrors;

/** Thiết bị đã đăng nhập (F-05): xem danh sách và thu hồi thiết bị khác khi mất máy. */
public class DevicesFragment extends Fragment {
    private FragmentDevicesBinding binding;
    private ProfileViewModel viewModel;
    private DeviceAdapter adapter;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentDevicesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, ProfileViewModel.FACTORY).get(ProfileViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        adapter = new DeviceAdapter(this::confirmRevoke);
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);
        // Đường kẻ giữa các thiết bị (theo Figma), thụt vào cho thẳng với chữ.
        MaterialDividerItemDecoration line = new MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL);
        line.setDividerInsetStart((int) (68 * getResources().getDisplayMetrics().density));
        line.setLastItemDecorated(false);
        binding.list.addItemDecoration(line);
        load();
    }

    private void load() {
        viewModel.getDevices().observe(getViewLifecycleOwner(), result -> {
            if (result.isSuccess()) adapter.submitList(result.data);
            else Snackbar.make(binding.getRoot(), AuthErrors.message(result.errorCode), Snackbar.LENGTH_LONG).show();
        });
    }

    /** Hỏi xác nhận trước khi thu hồi vì thiết bị đó sẽ bị đăng xuất ngay. */
    private void confirmRevoke(DeviceInfo device) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_revoke_title)
                .setMessage(getString(R.string.profile_revoke_message, device.name))
                .setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.profile_revoke, (d, w) ->
                        viewModel.revokeDevice(device.id).observe(getViewLifecycleOwner(), result -> {
                            if (result.isSuccess()) load();
                            else Snackbar.make(binding.getRoot(), AuthErrors.message(result.errorCode), Snackbar.LENGTH_LONG).show();
                        }))
                .show();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
