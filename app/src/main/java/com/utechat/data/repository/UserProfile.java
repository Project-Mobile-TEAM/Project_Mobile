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
    @Nullable public final String deletionScheduledAt; // ISO-8601: tài khoản đang chờ xoá vĩnh viễn vào lúc này; null nếu không.

    public UserProfile(String id, @Nullable String phone, String displayName, @Nullable String avatarUrl,
            boolean discoverable, boolean allowMessagesFromNonContacts) {
        this(id, phone, displayName, avatarUrl, discoverable, allowMessagesFromNonContacts, null);
    }

    public UserProfile(String id, @Nullable String phone, String displayName, @Nullable String avatarUrl,
            boolean discoverable, boolean allowMessagesFromNonContacts, @Nullable String deletionScheduledAt) {
        this.deletionScheduledAt = deletionScheduledAt;
        this.id = id;
        this.phone = phone;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.discoverable = discoverable;
        this.allowMessagesFromNonContacts = allowMessagesFromNonContacts;
    }

    /** Bản sao đã huỷ yêu cầu xoá tài khoản. */
    public UserProfile withoutDeletion() {
        return new UserProfile(id, phone, displayName, avatarUrl, discoverable, allowMessagesFromNonContacts, null);
    }

    /** Bản sao với các trường có thể sửa (PATCH /me). */
    public UserProfile with(String displayName, boolean allowMessagesFromNonContacts, boolean discoverable) {
        return new UserProfile(id, phone, displayName, avatarUrl, discoverable, allowMessagesFromNonContacts, deletionScheduledAt);
    }
}
