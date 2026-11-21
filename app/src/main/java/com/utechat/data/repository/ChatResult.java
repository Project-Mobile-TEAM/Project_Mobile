package com.utechat.data.repository;

import androidx.annotation.Nullable;

/**
 * Kết quả bất biến cho các thao tác nghiệp vụ chat (tạo phòng, đổi nhóm, tải lịch sử...):
 * chứa dữ liệu khi thành công hoặc errorCode (+ retryAfterSeconds nếu bị giới hạn tần suất 429).
 */
public final class ChatResult<T> {
    @Nullable public final T data;
    @Nullable public final String errorCode;
    @Nullable public final Long retryAfterSeconds;

    private ChatResult(@Nullable T data, @Nullable String errorCode, @Nullable Long retryAfterSeconds) {
        this.data = data;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static <T> ChatResult<T> ok(@Nullable T data) {
        return new ChatResult<>(data, null, null);
    }

    public static <T> ChatResult<T> error(String code) {
        return new ChatResult<>(null, code, null);
    }

    public static <T> ChatResult<T> error(String code, @Nullable Long retryAfterSeconds) {
        return new ChatResult<>(null, code, retryAfterSeconds);
    }

    public boolean isSuccess() {
        return errorCode == null;
    }
}
