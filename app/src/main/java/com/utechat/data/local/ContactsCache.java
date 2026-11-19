package com.utechat.data.local;

import com.utechat.data.local.entity.ContactEntity;
import com.utechat.data.local.entity.ContactRequestEntity;
import com.utechat.data.local.entity.UserEntity;
import java.util.List;
import java.util.Objects;

/** Mục 11: đối soát snapshot đã tải đủ trong một transaction Room; không gọi REST hoặc tự quyết định phiên. */
public final class ContactsCache {
    private final AppDatabase db;
    public ContactsCache(AppDatabase db) { this.db=Objects.requireNonNull(db); }

    /** Caller 12–13 phải kiểm phiên bằng cơ chế mục 14 trên DISK trước khi gọi phương thức này. */
    public void replace(List<UserEntity> profiles, List<ContactEntity> contacts, List<ContactRequestEntity> requests) {
        Objects.requireNonNull(profiles);
        Objects.requireNonNull(contacts);
        Objects.requireNonNull(requests);
        db.runInTransaction(() -> {
            // Giữ user_cache cho lịch sử chat; xóa quan hệ/lời mời vắng khỏi snapshot server.
            db.contactDao().clearContacts();
            db.contactDao().clearRequests();
            for(UserEntity user:profiles) db.userDao().upsertPreservingPrivacy(user);
            for(ContactEntity contact:contacts) db.contactDao().insert(contact);
            for(ContactRequestEntity request:requests) db.contactDao().insertRequest(request);
        });
    }
}
