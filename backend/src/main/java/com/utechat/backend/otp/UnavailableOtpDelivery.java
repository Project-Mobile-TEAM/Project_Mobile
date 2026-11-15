package com.utechat.backend.otp;

import com.utechat.backend.exception.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Ngoài profile dev chưa có cổng SMS thật: từ chối gửi OTP thay vì lặng lẽ không gửi (ADR-002). */
@Component
@Profile("!dev")
public class UnavailableOtpDelivery implements OtpDelivery {
    @Override public void send(String phoneE164, String code) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OTP_UNAVAILABLE", "OTP delivery is not configured");
    }
}
