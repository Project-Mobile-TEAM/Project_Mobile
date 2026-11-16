package com.utechat.backend.controller;

import com.utechat.backend.dto.AuthDtos.*;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.AccountService;
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

    public AccountController(AccountService account) {
        this.account = account;
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
