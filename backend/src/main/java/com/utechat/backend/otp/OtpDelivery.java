package com.utechat.backend.otp;

/** Cách "gửi" OTP tới người dùng (ADR-002): tách thành interface để sau này thay bằng cổng SMS thật. */
public interface OtpDelivery {
    /** Gửi mã OTP tới số điện thoại dạng E.164 (ví dụ +84912345678). */
    void send(String phoneE164, String code);
}
