package com.utechat.data.repository.real;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.repository.AuthRepository;

/** Khung xác thực thật cho Phase3; tuyệt đối không tự chuyển sang tài khoản giả. */
public class RealAuthRepository implements AuthRepository {
    private final UserDao users; // Điểm nối cache hồ sơ, không chứa mật khẩu.
    private final AuthApi api; // Điểm nối REST riêng của nhóm Auth.
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);

    public RealAuthRepository(UserDao users, AuthApi api) { this.users = users; this.api = api; }
    @Override public LiveData<UserEntity> login(String phoneE164, String password) {
        // shortcut: Phase3 triển khai OTP/JWT; hiện từ chối thay vì báo đăng nhập thành công giả.
        throw new UnsupportedOperationException("Xác thực thật được triển khai ở Phase 3");
    }
    @Override public LiveData<UserEntity> getCurrentUser() { return current; }
    @Override public void logout() { current.setValue(null); }
}
