package com.utechat.data.remote.api;

import com.utechat.data.remote.model.HealthDto;
import retrofit2.Call;
import retrofit2.http.GET;

/** Chỉ chứa endpoint hệ thống; Auth/Contact/Chat/Media có interface riêng. */
public interface ApiService {
    @GET("api/health") Call<HealthDto> getHealth(); // Liveness không chứng minh CSDL.
    @GET("api/health/ready") Call<HealthDto> getHealthReady(); // Readiness ping thật.
}
