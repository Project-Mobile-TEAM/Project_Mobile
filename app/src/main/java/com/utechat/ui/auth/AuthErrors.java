package com.utechat.ui.auth;

import androidx.annotation.StringRes;
import com.utechat.R;

/** Đổi mã lỗi cố định của server thành chuỗi theo ngôn ngữ (API_CONTRACT mục 1). */
public final class AuthErrors {
    private AuthErrors() { }

    @StringRes public static int message(String code) {
        if (code == null) return R.string.auth_error_generic;
        switch (code) {
            case "PHONE_TAKEN": return R.string.auth_error_phone_taken;
            case "OTP_INVALID": return R.string.auth_error_otp_invalid;
            case "OTP_EXPIRED": return R.string.auth_error_otp_expired;
            case "OTP_ATTEMPTS": return R.string.auth_error_otp_attempts;
            case "OTP_RATE_LIMIT": return R.string.auth_error_otp_rate_limit;
            case "BAD_CREDENTIALS": return R.string.auth_error_bad_credentials;
            case "LOCKED": return R.string.auth_error_locked;
            case "NETWORK": return R.string.auth_error_network;
            default: return R.string.auth_error_generic; // Gồm UNAVAILABLE và mọi mã chưa biết.
        }
    }
}
