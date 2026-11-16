package com.utechat.data.remote.model;

/** DTO Gson phản chiếu JSON của backend (API_CONTRACT 2.1 và 2.2), tách khỏi Entity Room. Gson bỏ qua trường null. */
public final class AuthDtos {
    private AuthDtos() { }

    // ---- Yêu cầu
    public static final class PhoneDeviceRequest {
        final String phone; final String deviceId;
        public PhoneDeviceRequest(String phone, String deviceId) { this.phone = phone; this.deviceId = deviceId; }
    }
    public static final class VerifyRequest {
        final String challengeId; final String code;
        public VerifyRequest(String challengeId, String code) { this.challengeId = challengeId; this.code = code; }
    }
    public static final class RegisterCompleteRequest {
        final String registrationToken; final String displayName; final String password; final String deviceName;
        public RegisterCompleteRequest(String t, String name, String password, String deviceName) {
            this.registrationToken = t; this.displayName = name; this.password = password; this.deviceName = deviceName;
        }
    }
    public static final class PasswordLoginRequest {
        final String phone; final String password; final String deviceId; final String deviceName;
        public PasswordLoginRequest(String phone, String password, String deviceId, String deviceName) {
            this.phone = phone; this.password = password; this.deviceId = deviceId; this.deviceName = deviceName;
        }
    }
    public static final class OtpLoginVerifyRequest {
        final String challengeId; final String code; final String deviceName;
        public OtpLoginVerifyRequest(String challengeId, String code, String deviceName) {
            this.challengeId = challengeId; this.code = code; this.deviceName = deviceName;
        }
    }
    public static final class PasswordResetCompleteRequest {
        final String resetToken; final String newPassword; final String deviceName;
        public PasswordResetCompleteRequest(String t, String newPassword, String deviceName) {
            this.resetToken = t; this.newPassword = newPassword; this.deviceName = deviceName;
        }
    }
    public static final class UpdateProfileRequest {
        final String displayName; final Boolean discoverable; final Boolean allowMessagesFromNonContacts;
        public UpdateProfileRequest(String name, boolean allowMessages, boolean discoverable) {
            this.displayName = name; this.allowMessagesFromNonContacts = allowMessages; this.discoverable = discoverable;
        }
    }
    public static final class ChangePasswordRequest {
        final String oldPassword; final String newPassword;
        public ChangePasswordRequest(String oldPassword, String newPassword) { this.oldPassword = oldPassword; this.newPassword = newPassword; }
    }

    // ---- Phản hồi
    public static final class DeleteAccountRequest {
        public final String password;
        public DeleteAccountRequest(String password) { this.password = password; }
    }

    public static final class OtpChallengeResponse { public String challengeId; public int expiresInSeconds; public String demoOtp; }
    public static final class RegistrationTokenResponse { public String registrationToken; }
    public static final class ResetTokenResponse { public String resetToken; }
    public static final class AuthSuccessResponse { public String state; public String token; public UserDto user; }
    public static final class UserDto {
        public String id; public String phone; public String displayName; public String avatarUrl;
        public boolean discoverable; public boolean allowMessagesFromNonContacts;
        public String deletionScheduledAt; // Khác null: tài khoản đang trong 15 ngày chờ xoá.
    }
    public static final class DeviceDto { public String id; public String name; public String platform; public String lastSeenAt; public boolean current; }

    /** Khung lỗi chuẩn { "error": { "code": ... } }; app chỉ dùng code (API_CONTRACT mục 1). */
    public static final class ErrorBody { public Error error; public static final class Error { public String code; } }
}
