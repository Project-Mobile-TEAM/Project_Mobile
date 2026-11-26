package com.utechat.backend.controller;

import com.utechat.backend.dto.AuthDtos.*;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.AccountService;
import com.utechat.backend.service.TwoFactorService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Endpoint tài khoản (API_CONTRACT 2.2). Mọi endpoint cần Bearer token; danh tính lấy từ token, không từ body. */
@RestController
@RequestMapping("/api")
public class AccountController {
    private final AccountService account;
    private final TwoFactorService twoFactor;

    public AccountController(AccountService account, TwoFactorService twoFactor) {
        this.account = account;
        this.twoFactor = twoFactor;
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedActor actor) {
        return account.getProfile(actor.userId());
    }

    @PatchMapping("/me")
    public UserDto updateMe(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody UpdateProfileRequest r) {
        return account.updateProfile(actor.userId(), r);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody ChangePasswordRequest r) {
        account.changePassword(actor, r.oldPassword(), r.newPassword());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody DeleteAccountRequest r) {
        account.scheduleDeletion(actor.userId(), r.password());
    }

    /** Huỷ yêu cầu xoá khi đăng nhập lại trong 15 ngày chờ. */
    @PostMapping("/me/cancel-deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(@AuthenticationPrincipal AuthenticatedActor actor) {
        account.cancelDeletion(actor.userId());
    }

    @GetMapping("/account/2fa")
    public TwoFactorStatusResponse twoFactorStatus(@AuthenticationPrincipal AuthenticatedActor actor) {
        return new TwoFactorStatusResponse(twoFactor.isEnabled(actor.userId()));
    }

    @PostMapping("/account/2fa/setup")
    public TwoFactorSetupResponse twoFactorSetup(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody TwoFactorSetupRequest r) {
        TwoFactorService.Setup s = twoFactor.setup(actor, r.password());
        return new TwoFactorSetupResponse(s.secret(), s.otpauthUri());
    }

    @PostMapping("/account/2fa/enable")
    public BackupCodesResponse twoFactorEnable(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody TwoFactorEnableRequest r) {
        return new BackupCodesResponse(twoFactor.enable(actor, r.code()));
    }

    @PostMapping("/account/2fa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void twoFactorDisable(@AuthenticationPrincipal AuthenticatedActor actor, @RequestBody TwoFactorDisableRequest r) {
        twoFactor.disable(actor, r.password(), r.code());
    }

    @GetMapping("/account/devices")
    public List<DeviceDto> devices(@AuthenticationPrincipal AuthenticatedActor actor) {
        return account.getDevices(actor);
    }

    @DeleteMapping("/account/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDevice(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id) {
        UUID target;
        try {
            target = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found");
        }
        account.revokeDevice(actor, target);
    }
}
