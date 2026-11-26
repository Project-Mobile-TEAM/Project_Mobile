package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Các DTO của xác thực và tài khoản (API_CONTRACT 2.1, 2.2): record bất biến, tên trường camelCase. */
public final class AuthDtos {
    private AuthDtos() { }

    // ---- Đăng ký (F-01)
    public record RegisterOtpRequest(String phone, String deviceId) { }
    /** demoOtp chỉ có khi server ở chế độ demo (ADR-002); thật thì null và bị bỏ khỏi JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OtpChallengeResponse(String challengeId, int expiresInSeconds, String demoOtp) { }
    public record VerifyRequest(String challengeId, String code) { }
    public record RegistrationTokenResponse(String registrationToken) { }
    public record RegisterCompleteRequest(String registrationToken, String displayName, String password, String deviceName) { }

    // ---- Đăng nhập (F-02, F-03)
    public record PasswordLoginRequest(String phone, String password, String deviceId, String deviceName) { }
    public record OtpRequest(String phone, String deviceId) { }
    public record OtpLoginVerifyRequest(String challengeId, String code, String deviceName) { }
    /** state = COMPLETE (có token, user) hoặc NEED_2FA (chỉ có secondFactorToken, hiệu lực 10 phút). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthSuccessResponse(String state, String token, UserDto user, String secondFactorToken) { }
    public record SecondFactorRequest(String secondFactorToken, String code, String deviceName) { }

    // ---- Quên mật khẩu (F-04)
    public record ResetTokenResponse(String resetToken) { }
    public record PasswordResetCompleteRequest(String resetToken, String newPassword, String deviceName) { }

    // ---- Tài khoản (F-05, F-08)
    /** deletionScheduledAt: null, hoặc thời điểm ISO-8601 tài khoản sẽ bị xoá vĩnh viễn (đang trong 15 ngày chờ). */
    public record UserDto(String id, String phone, String displayName, String avatarUrl,
                          boolean discoverable, boolean allowMessagesFromNonContacts, String deletionScheduledAt) { }
    /** Trường nào null thì giữ nguyên giá trị cũ. */
    public record UpdateProfileRequest(String displayName, String avatarMediaUrl,
                                       Boolean discoverable, Boolean allowMessagesFromNonContacts) { }
    public record ChangePasswordRequest(String oldPassword, String newPassword) { }
    public record DeleteAccountRequest(String password) { }
    // ---- 2FA TOTP (F-05, F-06)
    public record TwoFactorStatusResponse(boolean enabled) { }
    public record TwoFactorSetupRequest(String password) { }
    public record TwoFactorSetupResponse(String secret, String otpauthUri) { }
    public record TwoFactorEnableRequest(String code) { }
    public record BackupCodesResponse(java.util.List<String> backupCodes) { }
    public record TwoFactorDisableRequest(String password, String code) { }
    public record DeviceDto(String id, String name, String platform, String lastSeenAt, boolean current) { }
}
