package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.*;
import java.util.List;

/** Ranh giới AuthRepository: UI không phụ thuộc cách lưu SQLite hoặc gọi REST. */
public interface AuthRepository {
    // Đăng nhập bằng mật khẩu: lỗi BAD_CREDENTIALS, LOCKED, NETWORK...; bản Fake chỉ dành Debug.
    LiveData<AuthResult<UserEntity>> login(String phoneE164, String password);
    // Đăng nhập bằng OTP bước 1: gửi mã; số chưa đăng ký vẫn trả thành công để không lộ số có tồn tại.
    LiveData<AuthResult<OtpChallenge>> requestLoginOtp(String phoneE164);
    // Bước 2: xác minh mã, thành công thì thiết bị này đăng nhập; lỗi OTP_INVALID/OTP_EXPIRED/LOCKED.
    LiveData<AuthResult<UserEntity>> verifyLoginOtp(String challengeId, String code);
    LiveData<UserEntity> getCurrentUser();
    void logout();

    // Đăng ký bước 1 (F-01): gửi OTP tới số E.164; lỗi PHONE_TAKEN nếu số đã có tài khoản.
    LiveData<AuthResult<OtpChallenge>> requestRegisterOtp(String phoneE164);
    // Đăng ký bước 2: xác minh OTP, trả registrationToken dùng một lần; lỗi OTP_INVALID/OTP_EXPIRED.
    LiveData<AuthResult<String>> verifyRegisterOtp(String challengeId, String code);
    // Đăng ký bước 3: tạo tài khoản bằng registrationToken, tên hiển thị và mật khẩu.
    LiveData<AuthResult<UserEntity>> completeRegister(String registrationToken, String displayName, String password);

    // Quên mật khẩu (F-04) bước 1: gửi OTP; số chưa đăng ký vẫn trả thành công để không lộ số có tồn tại.
    LiveData<AuthResult<OtpChallenge>> requestResetOtp(String phoneE164);
    // Bước 2: xác minh OTP, trả resetToken dùng một lần.
    LiveData<AuthResult<String>> verifyResetOtp(String challengeId, String code);
    // Bước 3: đặt mật khẩu mới; thành công thì thiết bị hiện tại được đăng nhập.
    LiveData<AuthResult<UserEntity>> completePasswordReset(String resetToken, String newPassword);

    // Hồ sơ của chính mình (GET /me); giá trị null khi chưa đăng nhập.
    LiveData<UserProfile> getProfile();
    // Sửa tên và cờ riêng tư (PATCH /me).
    LiveData<AuthResult<UserProfile>> updateProfile(String displayName, boolean allowMessagesFromNonContacts, boolean discoverable);
    // Đổi mật khẩu (POST /me/password); lỗi BAD_CREDENTIALS nếu mật khẩu hiện tại sai.
    LiveData<AuthResult<Boolean>> changePassword(String oldPassword, String newPassword);
    // Yêu cầu xoá tài khoản (DELETE /me, F-08) sau khi nhập lại mật khẩu: server chờ 15 ngày rồi mới xoá vĩnh viễn;
    // thành công thì phiên cục bộ bị xoá (mọi thiết bị bị đăng xuất).
    LiveData<AuthResult<Boolean>> deleteAccount(String password);
    // Đăng nhập lại trong 15 ngày chờ và chọn giữ tài khoản (POST /me/cancel-deletion).
    LiveData<AuthResult<Boolean>> cancelDeletion();
    // Danh sách thiết bị đang đăng nhập (GET /account/devices).
    LiveData<AuthResult<List<DeviceInfo>>> getDevices();
    // Thu hồi một thiết bị khác (DELETE /account/devices/{id}).
    LiveData<AuthResult<Boolean>> revokeDevice(String deviceId);
}
