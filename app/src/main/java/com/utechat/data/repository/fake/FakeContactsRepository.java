package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ContactsRepository;
import java.util.ArrayList;
import java.util.List;

/** Danh bạ mẫu tuân theo seed: ba cặp bạn và Alice chặn Eve. */
public class FakeContactsRepository implements ContactsRepository {
    @Override public LiveData<List<UserEntity>> getFriends(String ownerId) {
        List<UserEntity> friends = new ArrayList<>();
        boolean member = DemoData.ALICE.equals(ownerId) || DemoData.BOB.equals(ownerId)
                || DemoData.CHARLIE.equals(ownerId);
        if (member) {
            for (UserEntity user : DemoData.users()) {
                if (!user.getId().equals(ownerId) && !user.getId().equals(DemoData.DAVID)
                        && !user.getId().equals(DemoData.EVE)) friends.add(user);
            }
        }
        return new MutableLiveData<>(friends);
    }

    @Override public LiveData<List<UserEntity>> getBlockedUsers(String ownerId) {
        List<UserEntity> blocked = new ArrayList<>();
        // Chặn có chiều: Eve không tự động chặn ngược Alice.
        if (DemoData.ALICE.equals(ownerId)) {
            for (UserEntity user : DemoData.users()) if (DemoData.EVE.equals(user.getId())) blocked.add(user);
        }
        return new MutableLiveData<>(blocked);
    }

    @Override public LiveData<UserEntity> searchByPhone(String phoneE164) {
        for (UserEntity user : DemoData.users()) {
            if (user.getPhone().equals(phoneE164)) return new MutableLiveData<>(user);
        }
        return new MutableLiveData<>(null);
    }
}
