package com.utechat.ui.splash;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.utechat.data.remote.model.HealthDto;
import com.utechat.data.repository.HealthRepository;
import retrofit2.Call;

/** Giữ trạng thái probe qua xoay màn hình và chặn callback cũ ghi đè lần retry mới. */
public class HealthViewModel extends ViewModel {
    public enum ProbeStatus { LOADING, SUCCESS, ERROR }
    private final HealthRepository repository;
    private final MutableLiveData<ProbeStatus> status = new MutableLiveData<>();
    private final MutableLiveData<HealthDto> healthData = new MutableLiveData<>();
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private Call<HealthDto> activeCall; // Hủy request khi retry hoặc ViewModel bị giải phóng.
    private int generation = 0; // Mỗi lần kiểm tra có số thứ tự để bỏ phản hồi đến trễ.

    public HealthViewModel(HealthRepository repository) { this.repository = repository; }
    public LiveData<ProbeStatus> getStatus() { return status; }
    public LiveData<HealthDto> getHealthData() { return healthData; }
    public LiveData<String> getErrorMessage() { return errorMessage; }

    public void checkHealth() {
        if (activeCall != null) activeCall.cancel();
        final int request = ++generation;
        healthData.setValue(null);
        errorMessage.setValue(null);
        status.setValue(ProbeStatus.LOADING);
        activeCall = repository.checkHealth(new HealthRepository.HealthCallback() {
            @Override public void onSuccess(HealthDto data) {
                if (request != generation) return;
                healthData.setValue(data);
                if (data != null && "UP".equals(data.getStatus()) && "CONNECTED".equals(data.getDatabase())) {
                    status.setValue(ProbeStatus.SUCCESS);
                } else {
                    errorMessage.setValue(HealthRepository.RESPONSE);
                    status.setValue(ProbeStatus.ERROR);
                }
            }
            @Override public void onError(String code) {
                if (request != generation) return;
                errorMessage.setValue(code);
                status.setValue(ProbeStatus.ERROR);
            }
        });
    }

    public void retry() { checkHealth(); }

    @Override protected void onCleared() {
        // Đổi generation trước cancel để callback đang xếp hàng cũng không cập nhật LiveData.
        generation++;
        if (activeCall != null) activeCall.cancel();
    }
}
