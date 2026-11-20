package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Tập hợp DTO bất biến (Java 17 record) cho toàn bộ REST API và WebSocket của Phase 5:
 * hội thoại 1-1/nhóm, tin nhắn, biên nhận, bootstrap snapshot, đồng bộ /api/sync và khung WS.
 */
public final class MessagingDtos {
    private MessagingDtos() {
    }

    /** Yêu cầu mở hoặc tạo hội thoại 1-1 với một người dùng theo UUID. */
    public record DirectConversationRequest(String userId) {
    }

    /** Yêu cầu tạo nhóm chat mới với tên nhóm và danh sách UUID thành viên bạn bè. */
    public record CreateGroupRequest(String title, List<String> memberIds) {
    }

    /** Yêu cầu cập nhật tên nhóm (ADMIN) hoặc bật/tắt thông báo cá nhân (muted). */
    public record UpdateConversationRequest(String title, Boolean muted) {
    }

    /** Yêu cầu thêm một hoặc nhiều bạn bè ACCEPTED vào nhóm chat. */
    public record AddMembersRequest(List<String> userIds) {
    }

    /** Yêu cầu thay đổi vai trò thành viên trong nhóm (ADMIN / MEMBER). */
    public record UpdateRoleRequest(String role) {
    }

    /**
     * Yêu cầu gửi tin nhắn từ client; Phase 5 chỉ nhận type = TEXT.
     * expectedVisibleFromMessageId bắt buộc với nhóm để chặn tin offline thuộc lần tham gia cũ.
     */
    public record SendMessageRequest(
            String clientId,
            String type,
            String text,
            String mediaUrl,
            Double latitude,
            Double longitude,
            Long expectedVisibleFromMessageId) {
    }

    /** Yêu cầu nâng mốc biên nhận DELIVERED hoặc READ lên tới upToMessageId. */
    public record WatermarkRequest(Long upToMessageId) {
    }

    /** Thông tin thành viên trong hội thoại, kèm mốc cô lập lịch sử và biên nhận. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberDto(
            String userId,
            String displayName,
            String avatarUrl,
            String role,
            long visibleFromMessageId,
            long lastDeliveredMessageId,
            long lastReadMessageId,
            boolean muted,
            String leftAt) {
    }

    /** Tóm tắt metadata cuộc gọi gắn với tin nhắn loại CALL (bảo toàn cho Phase 6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallSummaryDto(
            String id,
            String state,
            String media,
            String endReason) {
    }

    /** DTO tin nhắn đầy đủ, bảo toàn toàn bộ metadata media/call/location/system cho Phase 5 & 6. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageDto(
            long id,
            String clientId,
            String conversationId,
            String senderId,
            String type,
            String text,
            String mediaUrl,
            String mime,
            Long sizeBytes,
            String fileName,
            Integer durationMs,
            Integer width,
            Integer height,
            String systemEvent,
            String systemParams,
            CallSummaryDto call,
            Double latitude,
            Double longitude,
            String createdAt) {
    }

    /** DTO hội thoại trả về cho danh sách, chi tiết, bootstrap và sự kiện CONVERSATION_UPDATED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConversationDto(
            String id,
            String type,
            String title,
            String avatarUrl,
            Long lastMessageId,
            String createdAt,
            int unreadCount,
            boolean muted,
            long visibleFromMessageId,
            String leftAt,
            MessageDto lastMessage,
            List<MemberDto> members) {
    }

    /** Dòng bạn bè hoặc người bị chặn trong snapshot bootstrap (phone chỉ có khi là bạn bè). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BootstrapContactDto(
            String id,
            String phone,
            String displayName,
            String avatarUrl,
            boolean blocked,
            String createdAt) {
    }

    /** Hồ sơ tối thiểu không lộ SĐT dùng trong danh sách lời mời kết bạn của bootstrap. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BootstrapUserDto(
            String id,
            String displayName,
            String avatarUrl) {
    }

    /** Lời mời kết bạn PENDING trả về trong snapshot bootstrap. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BootstrapContactRequestDto(
            String id,
            BootstrapUserDto requester,
            BootstrapUserDto addressee,
            String status,
            String createdAt,
            String respondedAt) {
    }

    /** Snapshot khởi tạo nhất quán kèm syncCursor từ GET /api/sync/bootstrap. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BootstrapResponse(
            AuthDtos.UserDto me,
            List<ConversationDto> conversations,
            List<BootstrapContactDto> friends,
            List<BootstrapContactDto> blocked,
            List<BootstrapContactRequestDto> contactRequests,
            long syncCursor) {
    }

    /** Một sự kiện đồng bộ đọc từ bảng sync_events trong GET /api/sync. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SyncEventDto(
            long id,
            String eventType,
            String entityType,
            String entityId,
            JsonNode payload,
            String createdAt) {
    }

    /** Kết quả phân trang từ GET /api/sync kèm hasMore và retryAfterMs khi barrier đang giữ sự kiện. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SyncResponse(
            List<SyncEventDto> events,
            long nextCursor,
            boolean hasMore,
            Long retryAfterMs) {
    }

    /** Khung JSON chuẩn v1 trao đổi qua WebSocket thuần /ws. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WsFrame(
            int v,
            String type,
            String id,
            Long syncEventId,
            Object payload) {
    }
}
