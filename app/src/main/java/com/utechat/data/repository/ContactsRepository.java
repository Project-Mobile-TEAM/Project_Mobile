package com.utechat.data.repository;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.entity.*;
import java.util.List;

/** Ranh giới ContactsRepository: UI không phụ thuộc cách lưu SQLite hoặc gọi REST. */
public interface ContactsRepository {
    // Chữ ký thống nhất để nhóm danh bạ thay Fake bằng API mà không sửa ViewModel.
    LiveData<List<UserEntity>> getFriends(String ownerId);
    LiveData<List<UserEntity>> getBlockedUsers(String ownerId);
    LiveData<UserEntity> searchByPhone(String phoneE164);
}
