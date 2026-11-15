package com.utechat.data.repository;

import androidx.annotation.Nullable;

/** Hồ sơ của chính mình theo GET /me (API_CONTRACT 2.2), gồm cả cờ riêng tư mà UserEntity (cache người khác) không có. */
public final class UserProfile {
    public final String id;
    @Nullable public final String phone; // E.164, không đổi được.
    public final String displayName;
    @Nullable public final String avatarUrl;
    public final boolean discoverable; // Cho phép người khác tìm thấy qua số điện thoại.
    public final boolean allowMessagesFromNonContacts; // Cho phép người lạ nhắn tin.

    public UserProfile(String id, @Nullable String phone, String displayName, @Nullable String avatarUrl,
            boolean discoverable, boolean allowMessagesFromNonContacts) {
        this.id = id;
        this.phone = phone;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.discoverable = discoverable;
        this.allowMessagesFromNonContacts = allowMessagesFromNonContacts;
    }

    /** Bản sao với các trường có thể sửa (PATCH /me). */
    public UserProfile with(String displayName, boolean allowMessagesFromNonContacts, boolean discoverable) {
        return new UserProfile(id, phone, displayName, avatarUrl, discoverable, allowMessagesFromNonContacts);
    }
}
