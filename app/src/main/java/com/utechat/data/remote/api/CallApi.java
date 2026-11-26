package com.utechat.data.remote.api;

import com.utechat.data.remote.model.CallDtos;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Ranh giới Retrofit cho REST API vòng đời cuộc gọi thoại 1-1 (/api/calls/**).
 * Mọi đường dẫn đều tương đối ("api/...") để ghép chuẩn với BACKEND_BASE_URL.
 */
public interface CallApi {

    @POST("api/calls")
    Call<CallDtos.StartCallResponse> startCall(@Body CallDtos.StartCallRequest request);

    @POST("api/calls/{id}/accept")
    Call<CallDtos.AcceptCallResponse> acceptCall(@Path("id") String callId);

    @POST("api/calls/{id}/reject")
    Call<Void> rejectCall(@Path("id") String callId);

    @POST("api/calls/{id}/end")
    Call<Void> endCall(@Path("id") String callId);
}
