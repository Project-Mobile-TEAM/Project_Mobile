package com.utechat.ui.splash;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.remote.model.HealthDto;
import com.utechat.data.repository.HealthRepository;
import org.junit.Rule;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import retrofit2.Call;
import static org.junit.Assert.*;

/** Callback điều khiển được giúp kiểm cả phản hồi cũ đến trễ, không phụ thuộc mạng thật. */
public class HealthViewModelTest {
    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();
    private static class ProbeRepository extends HealthRepository {
        final List<HealthCallback> callbacks = new ArrayList<>();
        ProbeRepository() { super(null); }
        @Override public Call<HealthDto> checkHealth(HealthCallback callback) {
            callbacks.add(callback);
            return null;
        }
    }

    @Test public void successRequiresDatabaseConnected() {
        ProbeRepository repo = new ProbeRepository();
        HealthViewModel vm = new HealthViewModel(repo);
        vm.checkHealth();
        assertEquals(HealthViewModel.ProbeStatus.LOADING, vm.getStatus().getValue());
        repo.callbacks.get(0).onSuccess(new HealthDto("UP", "CONNECTED", "2026-11-11T00:00:00Z"));
        assertEquals(HealthViewModel.ProbeStatus.SUCCESS, vm.getStatus().getValue());
        assertEquals("CONNECTED", vm.getHealthData().getValue().getDatabase());
    }

    @Test public void errorsAndUnexpectedBodyDoNotBecomeSuccess() {
        ProbeRepository repo = new ProbeRepository();
        HealthViewModel vm = new HealthViewModel(repo);
        vm.checkHealth();
        repo.callbacks.get(0).onError(HealthRepository.NETWORK);
        assertEquals(HealthViewModel.ProbeStatus.ERROR, vm.getStatus().getValue());
        assertEquals(HealthRepository.NETWORK, vm.getErrorMessage().getValue());
        vm.retry();
        repo.callbacks.get(1).onSuccess(new HealthDto("UP", null, null));
        assertEquals(HealthViewModel.ProbeStatus.ERROR, vm.getStatus().getValue());
    }

    @Test public void retryIgnoresOldCallbacksAndClearedViewModel() {
        ProbeRepository repo = new ProbeRepository();
        HealthViewModel vm = new HealthViewModel(repo);
        vm.checkHealth();
        vm.retry();
        repo.callbacks.get(0).onError(HealthRepository.NETWORK);
        assertEquals(HealthViewModel.ProbeStatus.LOADING, vm.getStatus().getValue());
        vm.onCleared();
        repo.callbacks.get(1).onSuccess(new HealthDto("UP", "CONNECTED", null));
        assertEquals(HealthViewModel.ProbeStatus.LOADING, vm.getStatus().getValue());
    }
}
