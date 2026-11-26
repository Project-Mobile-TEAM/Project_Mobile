package com.utechat.data.repository;

/** Kết quả bước 1 bật 2FA: khoá bí mật Base32 (nhập tay) và đường dẫn otpauth:// (mở bằng ứng dụng xác thực). */
public final class TwoFactorSetup {
    public final String secret;
    public final String otpauthUri;

    public TwoFactorSetup(String secret, String otpauthUri) {
        this.secret = secret;
        this.otpauthUri = otpauthUri;
    }
}
