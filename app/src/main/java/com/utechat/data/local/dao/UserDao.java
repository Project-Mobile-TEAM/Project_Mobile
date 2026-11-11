package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** UserDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface UserDao {
    // Upsert cập nhật tại chỗ, tránh REPLACE xóa dòng rồi gây tác dụng phụ khóa ngoại.
    @Upsert void insert(UserEntity user);
    @Query("SELECT * FROM user_cache WHERE id = :id") LiveData<UserEntity> getUserById(String id);
    @Query("SELECT * FROM user_cache WHERE phone = :phone") LiveData<UserEntity> getUserByPhone(String phone);
}
