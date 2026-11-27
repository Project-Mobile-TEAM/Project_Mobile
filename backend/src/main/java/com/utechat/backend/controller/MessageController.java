package com.utechat.backend.controller;

import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.dto.MessagingDtos.ReactionRequest;
import com.utechat.backend.dto.MessagingDtos.ReactionUpdateResponse;
import com.utechat.backend.dto.MessagingDtos.SendMessageRequest;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.MessageService;
import com.utechat.backend.service.MessageService.SendResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho tài nguyên tin nhắn (/api/conversations/{id}/messages).
 */
@RestController
@RequestMapping("/api/conversations/{id}/messages")
public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** Tải lịch sử tin nhắn phân trang ngược theo cursor beforeId (mặc định 50, tối đa 100). */
    @GetMapping
    public List<MessageDto> history(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        UUID conversationId = ConversationController.parsePathUuid(id);
        return messageService.getMessages(actor.userId(), conversationId, before, limit);
    }

    /** Gửi tin nhắn TEXT: trả 201 khi tạo mới hoặc 200 khi trùng clientId (idempotent replay). */
    @PostMapping
    public ResponseEntity<MessageDto> send(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @PathVariable String id,
            @RequestBody(required = false) SendMessageRequest request) {
        UUID conversationId = ConversationController.parsePathUuid(id);
        SendResult result = messageService.sendMessage(actor.userId(), conversationId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.message());
    }

    /** Máy chủ kiểm người gửi, quyền hội thoại và hạn gỡ 24 giờ. */
    @PostMapping("/{messageId}/recall")
    public MessageDto recall(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id,
                             @PathVariable long messageId) {
        return messageService.recallMessage(actor.userId(), ConversationController.parsePathUuid(id), messageId);
    }

    /** Xóa riêng trên toàn bộ thiết bị của tài khoản đã xác thực. */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteForMe(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id,
                                             @PathVariable long messageId) {
        messageService.deleteMessageForMe(actor.userId(), ConversationController.parsePathUuid(id), messageId);
        return ResponseEntity.noContent().build();
    }

    /** Đổi hoặc bỏ một trong sáu biểu cảm được hỗ trợ. */
    @PutMapping("/{messageId}/reaction")
    public ReactionUpdateResponse reaction(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String id,
                                           @PathVariable long messageId,
                                           @RequestBody(required = false) ReactionRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Reaction payload is required");
        }
        return messageService.toggleReaction(actor.userId(), ConversationController.parsePathUuid(id),
                messageId, request.emoji());
    }
}
