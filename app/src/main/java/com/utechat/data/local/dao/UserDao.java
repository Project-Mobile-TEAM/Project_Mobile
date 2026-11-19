package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** UserDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface UserDao {
    // Phân biệt khóa chính với UNIQUE phone; không nuốt xung đột phone của hai actor khác nhau.
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertNew(UserEntity user);
    @Update(onConflict = OnConflictStrategy.ABORT) void updateExisting(UserEntity user);
    @Transaction default void insert(UserEntity user) {
        if(findById(user.getId())==null) insertNew(user);
        else updateExisting(user);
    }
    @Query("SELECT * FROM user_cache WHERE id = :id") LiveData<UserEntity> getUserById(String id);
    // Đọc đồng bộ trên luồng đĩa: hồ sơ của mình khi mở app offline.
    @Query("SELECT * FROM user_cache WHERE id = :id") UserEntity findById(String id);
    @Query("SELECT * FROM user_cache WHERE phone = :phone") LiveData<UserEntity> getUserByPhone(String phone);
    /** API danh bạ có thể thiếu phone hoặc quyền riêng tư; bảo toàn giá trị đã biết. */
    @Transaction default void upsertPreservingPrivacy(UserEntity user) {
        UserEntity old=findById(user.getId());
        // Tạo bản sao, không sửa DTO/entity mà caller còn dùng cho một phản hồi khác.
        UserEntity merged=new UserEntity();
        merged.setId(user.getId());
        merged.setDisplayName(user.getDisplayName());
        merged.setAvatarUrl(user.getAvatarUrl());
        merged.setPhone(user.getPhone());
        merged.setAllowMessagesFromNonContacts(user.getAllowMessagesFromNonContacts());
        if(old!=null) {
            merged.setAllowMessagesFromNonContacts(old.getAllowMessagesFromNonContacts());
            if(user.getPhone()==null) merged.setPhone(old.getPhone());
            if(user.getDisplayName()==null) merged.setDisplayName(old.getDisplayName());
            if(user.getAvatarUrl()==null) merged.setAvatarUrl(old.getAvatarUrl());
        }
        insert(merged);
    }
}
