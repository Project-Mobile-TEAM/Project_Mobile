package com.utechat.backend.controller;

import com.utechat.backend.dto.MessagingDtos.ConversationDto;
import com.utechat.backend.dto.MessagingDtos.DirectConversationRequest;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ConversationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho tài nguyên hội thoại (/api/conversations/**).
 * Danh tính người gọi luôn lấy duy nhất từ AuthenticatedActor (JWT), không đọc từ request body/query.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
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

    /** Lấy chi tiết một hội thoại kèm danh sách thành viên. */
    @GetMapping("/{id}")
    public ConversationDto detail(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id) {
        return conversationService.getConversation(actor.userId(), parsePathUuid(id));
    }

    /** Chuyển path variable sang UUID; trả 404 NOT_FOUND nếu chuỗi không hợp lệ. */
    static UUID parsePathUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
    }
}
