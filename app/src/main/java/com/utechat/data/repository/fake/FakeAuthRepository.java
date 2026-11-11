package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.AuthRepository;

/** Mô phỏng phiên in-memory; không phải xác thực hoặc JWT của server. */
public class FakeAuthRepository implements AuthRepository {
    private final MutableLiveData<UserEntity> current = new MutableLiveData<>(null);

    @Override public LiveData<UserEntity> login(String phoneE164, String password) {
        // Fake chọn hồ sơ bằng số chính xác; không kiểm mật khẩu và chỉ được bật ở Debug.
        UserEntity found = null;
        for (UserEntity user : DemoData.users()) {
            if (user.getPhone().equals(phoneE164)) found = user;
        }
        current.setValue(found);
        return current;
    }

    @Override public LiveData<UserEntity> getCurrentUser() { return current; }
    @Override public void logout() { current.setValue(null); }
}
