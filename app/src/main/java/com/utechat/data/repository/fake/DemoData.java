package com.utechat.data.repository.fake;

import com.utechat.data.local.entity.UserEntity;
import java.util.ArrayList;
import java.util.List;

/** Hồ sơ công khai của đúng5 tài khoản seed; không mang hash/password server xuống Android. */
public final class DemoData {
    public static final String ALICE = "11111111-1111-1111-1111-111111111111";
    public static final String BOB = "22222222-2222-2222-2222-222222222222";
    public static final String CHARLIE = "33333333-3333-3333-3333-333333333333";
    public static final String DAVID = "44444444-4444-4444-4444-444444444444";
    public static final String EVE = "55555555-5555-5555-5555-555555555555";
    public static final String DIRECT = "aaaaaaaa-1111-2222-0000-000000000001";
    public static final String GROUP = "bbbbbbbb-1111-3333-0000-000000000001";
    private DemoData() { }

    public static List<UserEntity> users() {
        // Tạo bản mới cho mỗi repository/test, tránh chia sẻ đối tượng có thể sửa đổi.
        List<UserEntity> users = new ArrayList<>();
        users.add(user(ALICE, "+84901111111", "Alice Nguyễn", true));
        users.add(user(BOB, "+84902222222", "Bob Trần", true));
        users.add(user(CHARLIE, "+84903333333", "Charlie Lê", true));
        users.add(user(DAVID, "+84904444444", "David Phạm", false));
        users.add(user(EVE, "+84905555555", "Eve Hoàng", true));
        return users;
    }

    private static UserEntity user(String id, String phone, String name, boolean strangers) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPhone(phone);
        user.setDisplayName(name);
        user.setAllowMessagesFromNonContacts(strangers);
        return user;
    }
}
