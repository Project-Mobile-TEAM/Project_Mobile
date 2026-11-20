package com.utechat.backend.controller;

import com.utechat.backend.dto.MessagingDtos.AddMembersRequest;
import com.utechat.backend.dto.MessagingDtos.ConversationDto;
import com.utechat.backend.dto.MessagingDtos.CreateGroupRequest;
import com.utechat.backend.dto.MessagingDtos.DirectConversationRequest;
import com.utechat.backend.dto.MessagingDtos.UpdateConversationRequest;
import com.utechat.backend.dto.MessagingDtos.UpdateRoleRequest;
import com.utechat.backend.dto.MessagingDtos.WatermarkRequest;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ConversationService;
import com.utechat.backend.service.ReceiptService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho hội thoại 1-1, nhóm chat và biên nhận DELIVERED/READ (/api/conversations/**).
 * Danh tính người gọi luôn lấy duy nhất từ AuthenticatedActor (JWT), không đọc từ body/query.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversationService;
    private final ReceiptService receiptService;

    public ConversationController(ConversationService conversationService, ReceiptService receiptService) {
        this.conversationService = conversationService;
        this.receiptService = receiptService;
    }

    /** Lấy danh sách hội thoại đang tham gia của người dùng hiện tại. */
    @GetMapping
    public List<ConversationDto> list(@AuthenticationPrincipal AuthenticatedActor actor) {
        return conversationService.listConversations(actor.userId());
    }

    /** Mở lại hội thoại 1-1 sẵn có hoặc tạo mới theo direct_key. */
    @PostMapping("/direct")
    public ConversationDto openOrCreateDirect(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestBody(required = false) DirectConversationRequest request) {
        String peerId = request == null ? null : request.userId();
        return conversationService.openOrCreateDirect(actor.userId(), peerId);
    }

    /** Tạo nhóm chat mới với ít nhất 2 bạn bè ACCEPTED (tối đa 50 thành viên). */
    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto createGroup(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestBody(required = false) CreateGroupRequest request) {
        return conversationService.createGroup(actor.userId(), request);
    }

    /** Lấy chi tiết một hội thoại kèm danh sách thành viên. */
    @GetMapping("/{id}")
    public ConversationDto detail(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id) {
        return conversationService.getConversation(actor.userId(), parsePathUuid(id));
    }

    /** Đổi tên nhóm (ADMIN) hoặc bật/tắt thông báo cá nhân (muted). */
    @PatchMapping("/{id}")
    public ConversationDto update(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestBody(required = false) UpdateConversationRequest request) {
        return conversationService.updateConversation(actor.userId(), parsePathUuid(id), request);
    }

    /** Thêm thành viên bạn bè ACCEPTED vào nhóm (chỉ ADMIN). */
    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMembers(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestBody(required = false) AddMembersRequest request) {
        conversationService.addMembers(actor.userId(), parsePathUuid(id), request);
    }

    /**
     * Xóa thành viên (ADMIN) hoặc tự rời nhóm (chính mình).
     * Nếu là ADMIN duy nhất còn thành viên khác, truyền successorUserId qua query param để bàn giao nguyên tử.
     */
    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeOrLeaveMember(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @PathVariable String userId,
            @RequestParam(value = "successorUserId", required = false) String successorUserId) {
        conversationService.removeOrLeaveMember(
                actor.userId(), parsePathUuid(id), parsePathUuid(userId), successorUserId);
    }

    /** Đổi vai trò thành viên nhóm (ADMIN / MEMBER). */
    @PutMapping("/{id}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRole(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @PathVariable String userId,
            @RequestBody(required = false) UpdateRoleRequest request) {
        conversationService.updateMemberRole(actor.userId(), parsePathUuid(id), parsePathUuid(userId), request);
    }

    /** Đánh dấu đã nhận tin nhắn (DELIVERED) tới mốc upToMessageId. */
    @PostMapping("/{id}/delivered")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markDelivered(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestBody(required = false) WatermarkRequest request) {
        Long upTo = request == null ? null : request.upToMessageId();
        receiptService.markDelivered(actor.userId(), parsePathUuid(id), upTo);
    }

    /** Đánh dấu đã đọc tin nhắn (READ) tới mốc upToMessageId (tự nâng DELIVERED >= READ). */
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestBody(required = false) WatermarkRequest request) {
        Long upTo = request == null ? null : request.upToMessageId();
        receiptService.markRead(actor.userId(), parsePathUuid(id), upTo);
    }

    /** Chuyển path variable sang UUID; trả 404 NOT_FOUND nếu chuỗi không hợp lệ. */
    static UUID parsePathUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
        }
    }
}
