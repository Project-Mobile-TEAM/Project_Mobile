package com.utechat.ui.splash;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import androidx.annotation.*;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import com.utechat.R;
import com.utechat.data.repository.HealthRepository;
import com.utechat.databinding.FragmentSplashBinding;
import com.utechat.di.Injection;
import com.utechat.di.ViewModelFactory;

/** Hiển thị probe thật; ViewModel giữ request khi xoay, Fragment chỉ quan sát và điều hướng. */
public class SplashFragment extends Fragment {
    private FragmentSplashBinding binding; // Không giữ binding sau onDestroyView.
    private HealthViewModel viewModel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::openAuth; // Chỉ một callback điều hướng đang chờ.

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this, new ViewModelFactory(
                Injection.provideHealthRepository())).get(HealthViewModel.class);
        binding.btnRetry.setOnClickListener(v -> viewModel.retry());
        binding.btnContinueOffline.setOnClickListener(v -> openAuth());
        // ViewLifecycleOwner tự gỡ observer khi View cũ bị hủy.
        viewModel.getStatus().observe(getViewLifecycleOwner(), this::render);
        if (viewModel.getStatus().getValue() == null) viewModel.checkHealth();
    }

    private void render(HealthViewModel.ProbeStatus status) {
        if (binding == null || status == null) return;
        handler.removeCallbacks(advance);
        boolean loading = status == HealthViewModel.ProbeStatus.LOADING;
        boolean ready = status == HealthViewModel.ProbeStatus.SUCCESS;
        boolean serverReached = ready || (!loading && !HealthRepository.NETWORK.equals(viewModel.getErrorMessage().getValue()));
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnRetry.setVisibility(!loading && !ready ? View.VISIBLE : View.GONE);
        binding.btnContinueOffline.setVisibility(!loading && !ready ? View.VISIBLE : View.GONE);
        binding.serverStatus.setText(loading ? R.string.probe_server_wait :
                serverReached ? R.string.probe_server_ok : R.string.probe_server_error);
        binding.databaseStatus.setText(loading ? R.string.probe_db_wait :
                ready ? R.string.probe_db_ok : R.string.probe_db_error);
        if (!loading) {
            binding.serverStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    serverReached ? R.color.probe_ok : R.color.probe_error));
            binding.databaseStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    ready ? R.color.probe_ok : R.color.probe_error));
        }
        binding.statusMessage.setText(loading ? R.string.probe_checking : ready ? R.string.probe_ready :
                serverReached ? R.string.probe_database_error : R.string.probe_network_error);
        if (ready) scheduleAdvance();
    }

    private void scheduleAdvance() {
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, 1500); // Cho người xem đủ thời gian đọc kết quả probe.
    }

    private void openAuth() {
        // Không điều hướng sau khi app ra nền, View bị hủy hoặc destination đã đổi.
        if (binding == null || !getViewLifecycleOwner().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        NavController controller = NavHostFragment.findNavController(this);
        if (controller.getCurrentDestination() != null && controller.getCurrentDestination().getId() == R.id.splashFragment) {
            controller.navigate(R.id.action_splash_to_auth);
        }
    }

    @Override public void onStart() {
        super.onStart();
        if (viewModel != null && viewModel.getStatus().getValue() == HealthViewModel.ProbeStatus.SUCCESS) scheduleAdvance();
    }
    @Override public void onStop() {
        handler.removeCallbacks(advance); // Không tự chuyển màn hình khi đang ở ứng dụng khác.
        super.onStop();
    }
    @Override public void onDestroyView() {
        handler.removeCallbacks(advance);
        super.onDestroyView();
        binding = null;
    }
}
