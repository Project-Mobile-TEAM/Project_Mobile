package com.utechat.data.remote.api;

import com.utechat.data.remote.model.AuthDtos.*;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.*;

/** Ranh giới Retrofit cho xác thực và tài khoản (API_CONTRACT 2.1, 2.2); đường dẫn tương đối theo BASE_URL. */
public interface AuthApi {
    @POST("api/auth/register/otp") Call<OtpChallengeResponse> registerOtp(@Body PhoneDeviceRequest body);
    @POST("api/auth/register/verify") Call<RegistrationTokenResponse> registerVerify(@Body VerifyRequest body);
    @POST("api/auth/register/complete") Call<AuthSuccessResponse> registerComplete(@Body RegisterCompleteRequest body);
    @POST("api/auth/login/password") Call<AuthSuccessResponse> loginPassword(@Body PasswordLoginRequest body);
    @POST("api/auth/password-reset/otp") Call<OtpChallengeResponse> resetOtp(@Body PhoneDeviceRequest body);
    @POST("api/auth/password-reset/verify") Call<ResetTokenResponse> resetVerify(@Body VerifyRequest body);
    @POST("api/auth/password-reset/complete") Call<AuthSuccessResponse> resetComplete(@Body PasswordResetCompleteRequest body);
    @POST("api/auth/logout") Call<Void> logout();

    @GET("api/me") Call<UserDto> me();
    @PATCH("api/me") Call<UserDto> updateMe(@Body UpdateProfileRequest body);
    @POST("api/me/password") Call<Void> changePassword(@Body ChangePasswordRequest body);
    @GET("api/account/devices") Call<List<DeviceDto>> devices();
    @DELETE("api/account/devices/{id}") Call<Void> revokeDevice(@Path("id") String id);
}
