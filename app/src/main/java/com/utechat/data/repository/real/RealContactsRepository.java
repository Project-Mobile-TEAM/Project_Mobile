package com.utechat.data.repository.real;

import androidx.lifecycle.LiveData;
import com.utechat.data.local.dao.ContactDao;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.ContactApi;
import com.utechat.data.repository.ContactsRepository;
import java.util.List;

/** Đọc cache thật; đồng bộ REST của danh bạ sẽ được bổ sung ở Phase4. */
public class RealContactsRepository implements ContactsRepository {
    private final ContactDao contacts; // Cache chỉ thuộc phiên đăng nhập hiện tại.
    private final UserDao users; // Hồ sơ công khai trong user_cache.
    private final ContactApi api; // Ranh giới mạng không dùng chung với Auth.
    public RealContactsRepository(ContactDao contacts, UserDao users, ContactApi api) {
        this.contacts = contacts; this.users = users; this.api = api;
    }
    @Override public LiveData<List<UserEntity>> getFriends(String ownerId) { return contacts.getFriends(); }
    @Override public LiveData<List<UserEntity>> getBlockedUsers(String ownerId) { return contacts.getBlockedUsers(); }
    @Override public LiveData<UserEntity> searchByPhone(String phoneE164) {
        // shortcut: Phase4 thêm lookup server; hiện chỉ tìm trong hồ sơ đã cache.
        return users.getUserByPhone(phoneE164);
    }
}
