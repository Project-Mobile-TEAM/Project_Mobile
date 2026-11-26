package com.utechat.backend.controller;

import com.utechat.backend.dto.AuthDtos.*;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Endpoint xác thực (API_CONTRACT 2.1). Tiền tố /api; chỉ /logout cần Bearer token. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register/otp")
    public OtpChallengeResponse registerOtp(@RequestBody RegisterOtpRequest r) {
        return auth.requestRegisterOtp(r.phone(), r.deviceId());
    }

    @PostMapping("/register/verify")
    public RegistrationTokenResponse registerVerify(@RequestBody VerifyRequest r) {
        return new RegistrationTokenResponse(auth.verifyRegisterOtp(r.challengeId(), r.code()));
    }

    @PostMapping("/register/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthSuccessResponse registerComplete(@RequestBody RegisterCompleteRequest r) {
        return auth.completeRegistration(r.registrationToken(), r.displayName(), r.password(), r.deviceName());
    }

    @PostMapping("/login/password")
    public AuthSuccessResponse loginPassword(@RequestBody PasswordLoginRequest r) {
        return auth.loginPassword(r.phone(), r.password(), r.deviceId(), r.deviceName());
    }

    @PostMapping("/login/second-factor")
    public AuthSuccessResponse loginSecondFactor(@RequestBody SecondFactorRequest r) {
        return auth.loginSecondFactor(r.secondFactorToken(), r.code(), r.deviceName());
    }

    @PostMapping("/login/otp/request")
    public OtpChallengeResponse loginOtpRequest(@RequestBody OtpRequest r) {
        return auth.requestLoginOtp(r.phone(), r.deviceId());
    }

    @PostMapping("/login/otp/verify")
    public AuthSuccessResponse loginOtpVerify(@RequestBody OtpLoginVerifyRequest r) {
        return auth.verifyLoginOtp(r.challengeId(), r.code(), r.deviceName());
    }

    @PostMapping("/password-reset/otp")
    public OtpChallengeResponse resetOtp(@RequestBody OtpRequest r) {
        return auth.requestPasswordResetOtp(r.phone(), r.deviceId());
    }

    @PostMapping("/password-reset/verify")
    public ResetTokenResponse resetVerify(@RequestBody VerifyRequest r) {
        return new ResetTokenResponse(auth.verifyPasswordResetOtp(r.challengeId(), r.code()));
    }

    @PostMapping("/password-reset/complete")
    public AuthSuccessResponse resetComplete(@RequestBody PasswordResetCompleteRequest r) {
        return auth.completePasswordReset(r.resetToken(), r.newPassword(), r.deviceName());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedActor actor) {
        auth.logout(actor);
    }
}
