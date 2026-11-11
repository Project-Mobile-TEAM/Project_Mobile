package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.*;
import java.util.List;

/** Ranh giới AuthRepository: UI không phụ thuộc cách lưu SQLite hoặc gọi REST. */
public interface AuthRepository {
    // Đăng nhập và quan sát phiên hiện tại; bản Fake chỉ dành Debug.
    LiveData<UserEntity> login(String phoneE164, String password);
    LiveData<UserEntity> getCurrentUser();
    void logout();
}
