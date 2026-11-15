package com.utechat.data.repository.real;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.data.repository.OtpChallenge;
import com.utechat.data.repository.UserProfile;
import java.util.List;

/** Khung xác thực thật cho Phase3; tuyệt đối không tự chuyển sang tài khoản giả. */
public class RealAuthRepository implements AuthRepository {
    private final UserDao users; // Điểm nối cache hồ sơ, không chứa mật khẩu.
    private final AuthApi api; // Điểm nối REST riêng của nhóm Auth.
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);

    public RealAuthRepository(UserDao users, AuthApi api) { this.users = users; this.api = api; }

    // shortcut: chưa gọi REST (backend 03-01 chưa có); trả lỗi UNAVAILABLE thay vì báo thành công giả.
    @Override public LiveData<UserEntity> login(String phoneE164, String password) {
        return new MutableLiveData<>(null);
    }
    @Override public LiveData<UserEntity> getCurrentUser() { return current; }
    @Override public void logout() { current.setValue(null); }

    @Override public LiveData<AuthResult<OtpChallenge>> requestRegisterOtp(String phoneE164) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<String>> verifyRegisterOtp(String challengeId, String code) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<UserEntity>> completeRegister(String registrationToken,
            String displayName, String password) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }

    @Override public LiveData<AuthResult<OtpChallenge>> requestResetOtp(String phoneE164) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<String>> verifyResetOtp(String challengeId, String code) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<UserEntity>> completePasswordReset(String resetToken, String newPassword) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }

    // shortcut: chưa gọi REST /me và /account/devices; trả UNAVAILABLE cho tới khi backend 03-01 có endpoint.
    @Override public LiveData<UserProfile> getProfile() { return new MutableLiveData<>(null); }
    @Override public LiveData<AuthResult<UserProfile>> updateProfile(String displayName,
            boolean allowMessagesFromNonContacts, boolean discoverable) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<Boolean>> changePassword(String oldPassword, String newPassword) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<List<DeviceInfo>>> getDevices() {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
    @Override public LiveData<AuthResult<Boolean>> revokeDevice(String deviceId) {
        return new MutableLiveData<>(AuthResult.error("UNAVAILABLE"));
    }
}
