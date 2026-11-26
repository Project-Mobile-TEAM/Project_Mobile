package com.utechat.backend.controller;

import com.utechat.backend.dto.CallDtos.AcceptCallResponse;
import com.utechat.backend.dto.CallDtos.StartCallRequest;
import com.utechat.backend.dto.CallDtos.StartCallResponse;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.CallService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho tài nguyên cuộc gọi thoại WebRTC 1-1 (/api/calls):
 * luôn lấy userId và deviceIdHash trực tiếp từ AuthenticatedActor (JWT) để chống giả mạo thiết bị.
 */
@RestController
@RequestMapping("/api/calls")
public class CallController {
    private final CallService callService;

    public CallController(CallService callService) {
        this.callService = callService;
    }

    /** Khởi tạo cuộc gọi thoại 1-1 (trả 201 Created kèm expiresAt và danh sách ICE servers). */
    @PostMapping
    public ResponseEntity<StartCallResponse> startCall(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestBody(required = false) StartCallRequest request) {
        StartCallResponse response = callService.startCall(actor.userId(), actor.deviceIdHash(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Người nhận chấp nhận cuộc gọi và khóa cố định phiên báo hiệu vào thiết bị hiện tại (200 OK). */
    @PostMapping("/{id}/accept")
    public ResponseEntity<AcceptCallResponse> acceptCall(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id) {
        AcceptCallResponse response = callService.acceptCall(actor.userId(), actor.deviceIdHash(), parseCallId(id));
        return ResponseEntity.ok(response);
    }

    /** Người nhận từ chối cuộc gọi đang đổ chuông (204 No Content). */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> rejectCall(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id) {
        callService.rejectCall(actor.userId(), actor.deviceIdHash(), parseCallId(id));
        return ResponseEntity.noContent().build();
    }

    /** Hủy cuộc gọi đang đổ chuông (chỉ caller device) hoặc kết thúc cuộc gọi đang đàm thoại (204 No Content). */
    @PostMapping("/{id}/end")
    public ResponseEntity<Void> endCall(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id) {
        callService.endCall(actor.userId(), actor.deviceIdHash(), parseCallId(id));
        return ResponseEntity.noContent().build();
    }

    /** Phân tích UUID cuộc gọi từ path; trả 404 CALL_NOT_FOUND nếu chuỗi không phải UUID hợp lệ. */
    private static UUID parseCallId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND", "Call not found");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND", "Call not found");
        }
    }
}
