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
    // Đọc từ cache, thay đổi quan hệ qua API và trả lỗi rõ ràng cho giao diện.
    LiveData<List<com.utechat.data.local.dao.ContactRequestWithUser>> getRequests();
    LiveData<Integer> incomingCount(String owner);
    LiveData<ContactsResult<Boolean>> refresh();
    LiveData<ContactsResult<com.utechat.data.remote.model.ContactDtos.Lookup>> lookup(String phone);
    LiveData<ContactsResult<Boolean>> act(String action,String id,com.utechat.data.remote.model.ContactDtos.User profile);
    String sessionKey();
    String currentUserId();
}
