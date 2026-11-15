package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.AuthRepository;
import com.utechat.data.repository.AuthResult;
import com.utechat.data.repository.OtpChallenge;
import java.util.UUID;

/** Mô phỏng phiên in-memory; không phải xác thực hoặc JWT của server. */
public class FakeAuthRepository implements AuthRepository {
    /** OTP cố định của bản Fake để thử giao diện; server thật sinh mã ngẫu nhiên. */
    public static final String DEMO_OTP = "123456";
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);
    private String pendingPhone; // Số đang đăng ký, nhớ giữa ba bước.

    @Override public LiveData<UserEntity> login(String phoneE164, String password) {
        // Fake chọn hồ sơ bằng số chính xác; không kiểm mật khẩu và chỉ được bật ở Debug.
        UserEntity found = null;
        for (UserEntity user : DemoData.users()) {
            if (user.getPhone().equals(phoneE164)) found = user;
        }
        current.setValue(found);
        // Trả LiveData mới cho mỗi lần gọi để màn hình không nhận lại kết quả cũ.
        return new MutableLiveData<>(found);
    }

    @Override public LiveData<UserEntity> getCurrentUser() { return current; }
    @Override public void logout() { current.setValue(null); }

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
        current.setValue(user);
        return new MutableLiveData<>(AuthResult.ok(user));
    }
}
