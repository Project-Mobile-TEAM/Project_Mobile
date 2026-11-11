package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** ContactDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface ContactDao {
    // Danh bạ chỉ chứa tài khoản hiện tại; user_cache cung cấp hồ sơ để hiển thị.
    @Upsert void insert(ContactEntity contact);
    @Upsert void insertRequest(ContactRequestEntity request);
    @Query("SELECT * FROM contact_request ORDER BY createdAt DESC") LiveData<List<ContactRequestEntity>> getRequests();
    @Query("SELECT u.* FROM user_cache u JOIN contact c ON c.contactUserId = u.id WHERE c.blocked = 0") LiveData<List<UserEntity>> getFriends();
    @Query("SELECT u.* FROM user_cache u JOIN contact c ON c.contactUserId = u.id WHERE c.blocked = 1") LiveData<List<UserEntity>> getBlockedUsers();
}
