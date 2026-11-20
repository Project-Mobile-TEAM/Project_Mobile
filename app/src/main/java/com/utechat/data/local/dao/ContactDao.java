package com.utechat.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;
import com.utechat.data.local.entity.*;
import com.utechat.data.local.MessageText;

/** ContactDao: giao tiếp SQLite có kiểm tra câu SQL khi biên dịch. */
@Dao
public interface ContactDao {
    // Đối soát cả snapshot trong transaction AppDatabase; không thay đổi schema v1.
    @Query("DELETE FROM contact") void clearContacts();
    @Query("DELETE FROM contact_request") void clearRequests();
    @Query("DELETE FROM contact WHERE contactUserId=:id") void deleteContact(String id);
    @Query("DELETE FROM contact WHERE contactUserId=:id AND blocked=0") void deleteFriend(String id);
    @Query("DELETE FROM contact_request WHERE id=:id") void deleteRequest(String id);
    @Query("DELETE FROM contact_request WHERE requesterId=:id OR addresseeId=:id") void deleteRequestsFor(String id);
    @Query("SELECT r.*, a.displayName requesterName, b.displayName addresseeName FROM contact_request r LEFT JOIN user_cache a ON a.id=r.requesterId LEFT JOIN user_cache b ON b.id=r.addresseeId WHERE r.status='PENDING' ORDER BY r.createdAt DESC")
    LiveData<List<ContactRequestWithUser>> getPendingRequestsWithUsers();
    @Query("SELECT COUNT(*) FROM contact_request WHERE addresseeId=:owner AND status='PENDING'") LiveData<Integer> incomingCount(String owner);
    // Danh bạ chỉ chứa tài khoản hiện tại; user_cache cung cấp hồ sơ để hiển thị.
    @Upsert void insert(ContactEntity contact);
    @Upsert void insertContacts(List<ContactEntity> contacts);
    @Upsert void insertRequest(ContactRequestEntity request);
    @Upsert void insertRequests(List<ContactRequestEntity> requests);
    @Query("SELECT * FROM contact_request ORDER BY createdAt DESC") LiveData<List<ContactRequestEntity>> getRequests();
    @Query("SELECT u.* FROM user_cache u JOIN contact c ON c.contactUserId = u.id WHERE c.blocked = 0") LiveData<List<UserEntity>> getFriends();
    @Query("SELECT u.* FROM user_cache u JOIN contact c ON c.contactUserId = u.id WHERE c.blocked = 1") LiveData<List<UserEntity>> getBlockedUsers();

    /** Thay thế toàn bộ ảnh chụp danh bạ từ /api/sync/bootstrap một cách nguyên tử, bảo toàn hồ sơ user_cache. */
    @Transaction
    default void replaceSnapshotContacts(List<ContactEntity> items, List<UserEntity> users, UserDao userDao) {
        if (users != null && !users.isEmpty() && userDao != null) {
            userDao.upsertPreservingPrivacy(users);
        }
        clearContacts();
        if (items != null && !items.isEmpty()) {
            insertContacts(items);
        }
    }

    /** Thay thế toàn bộ lời mời kết bạn đang chờ từ /api/sync/bootstrap trong cùng một transaction. */
    @Transaction
    default void replacePendingRequests(List<ContactRequestEntity> requests, List<UserEntity> users, UserDao userDao) {
        if (users != null && !users.isEmpty() && userDao != null) {
            userDao.upsertPreservingPrivacy(users);
        }
        clearRequests();
        if (requests != null && !requests.isEmpty()) {
            insertRequests(requests);
        }
    }
}
