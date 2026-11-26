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
    @POST("api/auth/login/second-factor") Call<AuthSuccessResponse> loginSecondFactor(@Body SecondFactorRequest body);
    @POST("api/auth/login/friend-step") Call<AuthSuccessResponse> loginFriendStep(@Body FriendStepRequest body);
    @POST("api/auth/login/otp/request") Call<OtpChallengeResponse> loginOtpRequest(@Body PhoneDeviceRequest body);
    @POST("api/auth/login/otp/verify") Call<AuthSuccessResponse> loginOtpVerify(@Body OtpLoginVerifyRequest body);
    @POST("api/auth/password-reset/otp") Call<OtpChallengeResponse> resetOtp(@Body PhoneDeviceRequest body);
    @POST("api/auth/password-reset/verify") Call<ResetTokenResponse> resetVerify(@Body VerifyRequest body);
    @POST("api/auth/password-reset/complete") Call<AuthSuccessResponse> resetComplete(@Body PasswordResetCompleteRequest body);
    // Gửi token cũ tường minh: phiên cục bộ đã bị xoá ngay khi bấm Đăng xuất nên interceptor không còn token để gắn.
    @POST("api/auth/logout") Call<Void> logout(@Header("Authorization") String bearer);

    @GET("api/me") Call<UserDto> me();
    @PATCH("api/me") Call<UserDto> updateMe(@Body UpdateProfileRequest body);
    // DELETE có body (mật khẩu xác nhận) nên phải dùng @HTTP thay cho @DELETE.
    @HTTP(method = "DELETE", path = "api/me", hasBody = true) Call<Void> deleteMe(@Body DeleteAccountRequest body);
    @POST("api/me/cancel-deletion") Call<Void> cancelDeletion();
    @POST("api/me/password") Call<Void> changePassword(@Body ChangePasswordRequest body);
    @GET("api/account/2fa") Call<TwoFactorStatusResponse> twoFactorStatus();
    @POST("api/account/2fa/setup") Call<TwoFactorSetupResponse> twoFactorSetup(@Body TwoFactorSetupRequest body);
    @POST("api/account/2fa/enable") Call<BackupCodesResponse> twoFactorEnable(@Body TwoFactorEnableRequest body);
    @POST("api/account/2fa/disable") Call<Void> twoFactorDisable(@Body TwoFactorDisableRequest body);
    @GET("api/account/devices") Call<List<DeviceDto>> devices();
    @DELETE("api/account/devices/{id}") Call<Void> revokeDevice(@Path("id") String id);
}
