package com.utechat.data.repository;

import com.utechat.data.remote.api.ApiService;
import com.utechat.data.remote.model.HealthDto;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Health luôn đi mạng thật, độc lập cờ Fake của các module nghiệp vụ. */
public class HealthRepository {
    public static final String NETWORK = "NETWORK";
    public static final String DATABASE = "DATABASE";
    public static final String RESPONSE = "RESPONSE";
    private final ApiService api;

    public HealthRepository(ApiService api) { this.api = api; }
    public interface HealthCallback {
        void onSuccess(HealthDto data);
        void onError(String code); // Mã cố định được UI dịch; không hiển thị exception message.
    }

    public Call<HealthDto> checkHealth(HealthCallback callback) {
        Call<HealthDto> call = api.getHealthReady();
        call.enqueue(new Callback<HealthDto>() {
            @Override public void onResponse(Call<HealthDto> request, Response<HealthDto> response) {
                if (request.isCanceled()) return;
                if (response.isSuccessful() && response.body() != null) callback.onSuccess(response.body());
                else callback.onError(response.code() == 503 ? DATABASE : RESPONSE);
            }
            @Override public void onFailure(Call<HealthDto> request, Throwable error) {
                // Request bị hủy khi rời màn hình không được báo như lỗi mạng mới.
                if (!request.isCanceled()) callback.onError(NETWORK);
            }
        });
        return call;
    }
}
