package com.utechat.data.repository;

import androidx.annotation.Nullable;

/** Phiên OTP do server tạo; demoCode chỉ có khi server ở chế độ demo (ADR-002), thật thì là null. */
public final class OtpChallenge {
    public final String challengeId; // Mã phiên gửi kèm khi xác minh OTP.
    @Nullable public final String demoCode; // OTP demo để hiện trên màn hình, không có SMS thật.

    public OtpChallenge(String challengeId, @Nullable String demoCode) {
        this.challengeId = challengeId;
        this.demoCode = demoCode;
    }
}
