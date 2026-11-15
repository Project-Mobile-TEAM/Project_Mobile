package com.utechat.data.repository;

import androidx.annotation.Nullable;

/** Kết quả một thao tác xác thực: hoặc có dữ liệu, hoặc có mã lỗi (ví dụ PHONE_TAKEN, OTP_INVALID). */
public final class AuthResult<T> {
    @Nullable public final T data; // Dữ liệu trả về khi thành công.
    @Nullable public final String errorCode; // Mã lỗi theo API_CONTRACT; null nghĩa là thành công.

    private AuthResult(@Nullable T data, @Nullable String errorCode) {
        this.data = data;
        this.errorCode = errorCode;
    }

    public static <T> AuthResult<T> ok(T data) { return new AuthResult<>(data, null); }
    public static <T> AuthResult<T> error(String code) { return new AuthResult<>(null, code); }
    public boolean isSuccess() { return errorCode == null; }
}
