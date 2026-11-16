package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.DeviceInfo;
import com.utechat.data.repository.OtpChallenge;
import com.utechat.data.repository.UserProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mô phỏng phiên in-memory; không phải xác thực hoặc JWT của server. */
public class FakeAuthRepository implements AuthRepository {
    /** OTP cố định của bản Fake để thử giao diện; server thật sinh mã ngẫu nhiên. */
    public static final String DEMO_OTP = "123456";
    /** Mật khẩu hiện tại giả của mọi tài khoản demo (login Fake không kiểm mật khẩu), dùng để thử màn Đổi mật khẩu. */
    public static final String DEMO_PASSWORD = "demo1234";
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);
    private String pendingPhone; // Số đang đăng ký, nhớ giữa ba bước.
    private final MutableLiveData<UserProfile> profile = new MutableLiveData<>(null);
    private String password = DEMO_PASSWORD; // Mật khẩu hiện tại giả; đổi được qua changePassword.
    private final List<DeviceInfo> devices = new ArrayList<>();
    private UserEntity resetUser; // Tài khoản đang đặt lại mật khẩu; null nếu số chưa đăng ký.

    @Override public LiveData<AuthResult<UserEntity>> login(String phoneE164, String password) {
        // Fake chọn hồ sơ bằng số chính xác; không kiểm mật khẩu và chỉ được bật ở Debug.
        UserEntity found = null;
        for (UserEntity user : DemoData.users()) {
            if (user.getPhone().equals(phoneE164)) found = user;
        }
        setCurrent(found);
        // Trả LiveData mới cho mỗi lần gọi để màn hình không nhận lại kết quả cũ.
        return new MutableLiveData<>(found == null ? AuthResult.error("BAD_CREDENTIALS") : AuthResult.ok(found));
    }

    @Override public LiveData<AuthResult<OtpChallenge>> requestLoginOtp(String phoneE164) {
        return requestResetOtp(phoneE164); // Cùng cách tìm tài khoản theo số như quên mật khẩu.
    }

    @Override public LiveData<AuthResult<UserEntity>> verifyLoginOtp(String challengeId, String code) {
        if (resetUser == null || !DEMO_OTP.equals(code)) return new MutableLiveData<>(AuthResult.error("OTP_INVALID"));
        setCurrent(resetUser);
        return new MutableLiveData<>(AuthResult.ok(resetUser));
    }

    @Override public LiveData<UserEntity> getCurrentUser() { return current; }
    @Override public void logout() { setCurrent(null); }

    @Override public LiveData<AuthResult<OtpChallenge>> requestRegisterOtp(String phoneE164) {
        for (UserEntity user : DemoData.users()) {
            if (phoneE164.equals(user.getPhone())) return new MutableLiveData<>(AuthResult.error("PHONE_TAKEN"));
        }
        pendingPhone = phoneE164;
        return new MutableLiveData<>(AuthResult.ok(new OtpChallenge("fake-challenge", DEMO_OTP)));
    }

    @Override public LiveData<AuthResult<String>> verifyRegisterOtp(String challengeId, String code) {
        return new MutableLiveData<>(DEMO_OTP.equals(code)
                ? AuthResult.ok("fake-registration-token") : AuthResult.error("OTP_INVALID"));
    }

    @Override public LiveData<AuthResult<UserEntity>> completeRegister(String registrationToken,
            String displayName, String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setPhone(pendingPhone);
        user.setDisplayName(displayName);
        user.setAllowMessagesFromNonContacts(true); // Mặc định theo F-01.
        setCurrent(user);
        return new MutableLiveData<>(AuthResult.ok(user));
    }

    @Override public LiveData<AuthResult<OtpChallenge>> requestResetOtp(String phoneE164) {
        // Luôn trả thành công (không lộ số có tồn tại); số lạ thì bước xác minh sẽ không bao giờ đúng.
        resetUser = null;
        for (UserEntity user : DemoData.users()) {
            if (phoneE164.equals(user.getPhone())) resetUser = user;
        }
        return new MutableLiveData<>(AuthResult.ok(new OtpChallenge("fake-reset-challenge", DEMO_OTP)));
    }

    @Override public LiveData<AuthResult<String>> verifyResetOtp(String challengeId, String code) {
        return new MutableLiveData<>(resetUser != null && DEMO_OTP.equals(code)
                ? AuthResult.ok("fake-reset-token") : AuthResult.error("OTP_INVALID"));
    }

    @Override public LiveData<AuthResult<UserEntity>> completePasswordReset(String resetToken, String newPassword) {
        if (resetUser == null) return new MutableLiveData<>(AuthResult.error("VALIDATION"));
        setCurrent(resetUser);
        return new MutableLiveData<>(AuthResult.ok(resetUser));
    }

    // Cập nhật phiên hiện tại và hồ sơ /me tương ứng (mặc định cho phép tìm qua SĐT).
    private void setCurrent(UserEntity user) {
        current.setValue(user);
        password = DEMO_PASSWORD;
        profile.setValue(user == null ? null : new UserProfile(user.getId(), user.getPhone(),
                user.getDisplayName(), user.getAvatarUrl(), true, user.getAllowMessagesFromNonContacts()));
        devices.clear();
        if (user != null) {
            long now = System.currentTimeMillis();
            devices.add(new DeviceInfo("d1", "Pixel 7", "Android 14", now, true));
            devices.add(new DeviceInfo("d2", "Samsung A54", "Android 13", now - 2L * 86_400_000L, false));
            devices.add(new DeviceInfo("d3", "Xiaomi 12", "Android 12", now - 7L * 86_400_000L, false));
        }
    }

    @Override public LiveData<UserProfile> getProfile() { return profile; }

    @Override public LiveData<AuthResult<UserProfile>> updateProfile(String displayName,
            boolean allowMessagesFromNonContacts, boolean discoverable) {
        UserProfile old = profile.getValue();
        if (old == null) return new MutableLiveData<>(AuthResult.error("SESSION_EXPIRED"));
        UserProfile updated = old.with(displayName, allowMessagesFromNonContacts, discoverable);
        profile.setValue(updated);
        return new MutableLiveData<>(AuthResult.ok(updated));
    }

    @Override public LiveData<AuthResult<Boolean>> changePassword(String oldPassword, String newPassword) {
        if (!password.equals(oldPassword)) return new MutableLiveData<>(AuthResult.error("BAD_CREDENTIALS"));
        password = newPassword;
        return new MutableLiveData<>(AuthResult.ok(true));
    }

    @Override public LiveData<AuthResult<Boolean>> deleteAccount(String password) {
        if (!this.password.equals(password)) return new MutableLiveData<>(AuthResult.error("BAD_CREDENTIALS"));
        setCurrent(null);
        return new MutableLiveData<>(AuthResult.ok(true));
    }

    @Override public LiveData<AuthResult<List<DeviceInfo>>> getDevices() {
        return new MutableLiveData<>(AuthResult.ok(new ArrayList<>(devices))); // Bản sao để DiffUtil thấy thay đổi.
    }

    @Override public LiveData<AuthResult<Boolean>> revokeDevice(String deviceId) {
        for (DeviceInfo device : devices) {
            if (device.id.equals(deviceId) && !device.current) {
                devices.remove(device);
                return new MutableLiveData<>(AuthResult.ok(true));
            }
        }
        return new MutableLiveData<>(AuthResult.error("NOT_FOUND"));
    }
}
