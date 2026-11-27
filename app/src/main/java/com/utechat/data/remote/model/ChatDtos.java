package com.utechat.data.remote.model;

import androidx.annotation.Nullable;
import com.google.gson.JsonElement;
import java.util.List;

/**
 * Tập hợp DTO Gson cho toàn bộ REST API, bootstrap, /api/sync và khung WebSocket của Phase 5.
 * Tách biệt hoàn toàn khỏi Room Entity và bảo toàn trọn vẹn metadata của Phase 6 (media, call, location, system).
 */
public final class ChatDtos {
    private ChatDtos() { }

    // ======================== REQUEST DTOs ========================

    /** Yêu cầu mở hoặc tạo mới hội thoại 1-1 với người dùng có UUID userId. */
    public static final class DirectConversationRequest {
        public final String userId;

        public DirectConversationRequest(String userId) {
            this.userId = userId;
        }
    }

    /** Yêu cầu tạo nhóm chat mới với tiêu đề và danh sách UUID thành viên bạn bè. */
    public static final class CreateGroupRequest {
        public final String title;
        public final List<String> memberIds;

        public CreateGroupRequest(String title, List<String> memberIds) {
            this.title = title;
            this.memberIds = memberIds;
        }
    }

    /** Yêu cầu đổi tên nhóm (ADMIN) hoặc bật/tắt thông báo cá nhân (muted). */
    public static final class UpdateConversationRequest {
        @Nullable public final String title;
        @Nullable public final Boolean muted;

        public UpdateConversationRequest(@Nullable String title, @Nullable Boolean muted) {
            this.title = title;
            this.muted = muted;
        }
    }

    /** Yêu cầu thêm thành viên mới vào nhóm chat. */
    public static final class AddMembersRequest {
        public final List<String> userIds;

        public AddMembersRequest(List<String> userIds) {
            this.userIds = userIds;
        }
    }

    /** Yêu cầu đổi vai trò thành viên nhóm (ADMIN hoặc MEMBER). */
    public static final class UpdateRoleRequest {
        public final String role;

        public UpdateRoleRequest(String role) {
            this.role = role;
        }
    }

    /**
     * Yêu cầu gửi tin nhắn; mang theo clientId chống gửi trùng và expectedVisibleFromMessageId
     * để server kiểm tra đúng lần tham gia nhóm, tránh gửi nhầm tin offline thuộc lần tham gia cũ.
     */
    public static final class SendMessageRequest {
        public final String clientId;
        public final String type;
        @Nullable public final String text;
        @Nullable public final String mediaUrl;
        @Nullable public final Double latitude;
        @Nullable public final Double longitude;
        @Nullable public final Long expectedVisibleFromMessageId;
        @Nullable public final String stickerId;

        public SendMessageRequest(
                String clientId,
                String type,
                @Nullable String text,
                @Nullable String mediaUrl,
                @Nullable Double latitude,
                @Nullable Double longitude,
                @Nullable Long expectedVisibleFromMessageId) {
            this(clientId, type, text, mediaUrl, latitude, longitude, expectedVisibleFromMessageId, null);
        }

        // Chỉ tin STICKER mang UUID nhãn dán, giữ nguyên cách tạo yêu cầu của các loại tin hiện có.
        public SendMessageRequest(
                String clientId,
                String type,
                @Nullable String text,
                @Nullable String mediaUrl,
                @Nullable Double latitude,
                @Nullable Double longitude,
                @Nullable Long expectedVisibleFromMessageId,
                @Nullable String stickerId) {
            this.clientId = clientId;
            this.type = type;
            this.text = text;
            this.mediaUrl = mediaUrl;
            this.latitude = latitude;
            this.longitude = longitude;
            this.expectedVisibleFromMessageId = expectedVisibleFromMessageId;
            this.stickerId = stickerId;
        }
    }

    /** Gửi emoji để chọn hoặc đổi cảm xúc, hoặc null để bỏ cảm xúc hiện tại. */
    public static final class ReactionRequest {
        @Nullable public final String emoji;

        public ReactionRequest(@Nullable String emoji) {
            this.emoji = emoji;
        }
    }

    /** Yêu cầu cập nhật mốc biên nhận DELIVERED hoặc READ lên tới upToMessageId. */
    public static final class WatermarkRequest {
        public final long upToMessageId;

        public WatermarkRequest(long upToMessageId) {
            this.upToMessageId = upToMessageId;
        }
    }

    // ======================== RESPONSE & PAYLOAD DTOs ========================

    /** Tóm tắt metadata cuộc gọi trong tin nhắn loại CALL (bảo toàn 100% cho Phase 6). */
    public static final class CallSummaryDto {
        public String id;
        public String state;
        public String media;
        @Nullable public String endReason;
    }

    /** DTO tin nhắn đầy đủ từ server, giữ nguyên toàn bộ trường media/call/location/system. */
    public static final class MessageDto {
        public long id;
        public String clientId;
        public String conversationId;
        public String senderId;
        public String type;
        @Nullable public String text;
        @Nullable public String mediaUrl;
        @Nullable public String mime;
        @Nullable public Long sizeBytes;
        @Nullable public String fileName;
        @Nullable public Integer durationMs;
        @Nullable public Integer width;
        @Nullable public Integer height;
        @Nullable public String systemEvent;
        @Nullable public String systemParams;
        @Nullable public CallSummaryDto call;
        @Nullable public Double latitude;
        @Nullable public Double longitude;
        @Nullable public String createdAt;
        @Nullable public String recalledAt;
        @Nullable public StickerSummaryDto sticker;
        @Nullable public List<ReactionDto> reactions;
    }

    /** Metadata nhãn dán đi kèm tin nhắn, kể cả sau khi gỡ bộ khỏi khay. */
    public static final class StickerSummaryDto {
        public String id;
        public String packId;
        public String code;
        public String label;
        public String imageUrl;
        @Nullable public Integer width;
        @Nullable public Integer height;
    }

    /** Cảm xúc đã lưu của một người trong ảnh chụp tin nhắn. */
    public static final class ReactionDto {
        public String userId;
        public String emoji;
        public String createdAt;
    }

    /** Kết quả sau thao tác, kèm danh sách cảm xúc hiện tại của tin. */
    public static final class ReactionUpdateResponse {
        public long messageId;
        @Nullable public String emoji;
        public String action;
        public List<ReactionDto> reactions;
    }

    /** Giữ ID và vị trí tin tại chỗ khi nhận MESSAGE_RECALLED. */
    public static final class MessageRecalledPayload {
        public long messageId;
        public String conversationId;
        public String recalledAt;
        public String recalledBy;
    }

    /** Sự kiện xóa tin riêng cho các thiết bị của người thực hiện. */
    public static final class MessageDeletedForMePayload {
        public long messageId;
        public String conversationId;
        public String deletedAt;
    }

    /** Thay đổi của một người, emoji null hoặc vắng nghĩa là đã bỏ cảm xúc. */
    public static final class MessageReactionUpdatedPayload {
        public long messageId;
        public String conversationId;
        public String userId;
        @Nullable public String emoji;
        public String createdAt;
    }

    /** DTO thành viên hội thoại kèm mốc cô lập lịch sử nhóm và mốc biên nhận. */
    public static final class MemberDto {
        public String userId;
        @Nullable public String displayName;
        @Nullable public String avatarUrl;
        public String role;
        public long visibleFromMessageId;
        public long lastDeliveredMessageId;
        public long lastReadMessageId;
        public boolean muted;
        @Nullable public String leftAt;
    }

    /** DTO hội thoại trả về từ danh sách, chi tiết, bootstrap và sự kiện CONVERSATION_UPDATED. */
    public static final class ConversationDto {
        public String id;
        public String type;
        @Nullable public String title;
        @Nullable public String avatarUrl;
        @Nullable public Long lastMessageId;
        @Nullable public String createdAt;
        public int unreadCount;
        public boolean muted;
        public long visibleFromMessageId;
        @Nullable public String leftAt;
        @Nullable public MessageDto lastMessage;
        @Nullable public List<MemberDto> members;
    }

    /** DTO dòng bạn bè hoặc người bị chặn trong snapshot bootstrap và sự kiện CONTACT_UPDATED. */
    public static final class BootstrapContactDto {
        public String id;
        @Nullable public String phone;
        @Nullable public String displayName;
        @Nullable public String avatarUrl;
        public boolean blocked;
        @Nullable public String createdAt;
    }

    /** Hồ sơ tối giản không chứa số điện thoại dùng trong danh sách lời mời kết bạn. */
    public static final class BootstrapUserDto {
        public String id;
        @Nullable public String displayName;
        @Nullable public String avatarUrl;
    }

    /** DTO lời mời kết bạn trả về trong bootstrap và sự kiện CONTACT_REQUEST_CREATED/UPDATED. */
    public static final class BootstrapContactRequestDto {
        public String id;
        @Nullable public BootstrapUserDto requester;
        @Nullable public BootstrapUserDto addressee;
        public String status;
        @Nullable public String createdAt;
        @Nullable public String respondedAt;
    }

    /** Ảnh chụp khởi tạo nhất quán từ GET /api/sync/bootstrap kèm con trỏ syncCursor. */
    public static final class BootstrapResponse {
        @Nullable public AuthDtos.UserDto me;
        @Nullable public List<ConversationDto> conversations;
        @Nullable public List<BootstrapContactDto> friends;
        @Nullable public List<BootstrapContactDto> blocked;
        @Nullable public List<BootstrapContactRequestDto> contactRequests;
        public long syncCursor;
    }

    /** Một sự kiện đồng bộ đọc từ bảng sync_events trong GET /api/sync. */
    public static final class SyncEventDto {
        public long id;
        public String eventType;
        public String entityType;
        public String entityId;
        @Nullable public JsonElement payload;
        @Nullable public String createdAt;
    }

    /** Kết quả phân trang từ GET /api/sync kèm cờ hasMore và thời gian chờ barrier retryAfterMs. */
    public static final class SyncResponse {
        @Nullable public List<SyncEventDto> events;
        public long nextCursor;
        public boolean hasMore;
        @Nullable public Long retryAfterMs;
    }

    /** Khung JSON chuẩn v1 truyền trên kết nối WebSocket thuần /ws. */
    public static final class WsEnvelope {
        public int v;
        public String type;
        @Nullable public String id;
        @Nullable public Long syncEventId;
        @Nullable public JsonElement payload;
    }

    /** Payload của sự kiện RECEIPT_UPDATED. */
    public static final class ReceiptUpdatePayload {
        public String conversationId;
        public String userId;
        public long deliveredUpTo;
        public long readUpTo;
    }

    /** Payload của khung WebSocket typing (tạm thời, không lưu DB). */
    public static final class TypingPayload {
        public String conversationId;
        public String userId;
        public String state;
    }

    /** Payload của sự kiện CONTACT_UPDATED (tương thích 100% với Phase 4 ContactsService). */
    public static final class ContactUpdatePayload {
        public String contactUserId;
        public boolean isFriend;
        public boolean blocked;
        public boolean removed;
        @Nullable public BootstrapContactDto user;
    }

    /** Payload của sự kiện CALL_UPDATED (tương thích với V2 demo seed và Phase 6). */
    public static final class CallUpdatePayload {
        public String callId;
        @Nullable public String state;
        @Nullable public String endReason;
    }

    /** Payload của sự kiện DEVICE_REVOKED (thu hồi thiết bị đa máy). */
    public static final class DeviceRevokedPayload {
        @Nullable public String deviceId;
        @Nullable public String deviceIdHash;
        @Nullable public String reason;
    }

    /** Khung lỗi JSON chuẩn { "error": { "code", "message", "details": { ... } } } của backend. */
    public static final class ErrorBody {
        @Nullable public ErrorObj error;
    }

    /** Nội dung đối tượng lỗi bên trong ErrorBody. */
    public static final class ErrorObj {
        @Nullable public String code;
        @Nullable public String message;
        @Nullable public ErrorDetails details;
    }

    /** Chi tiết bổ sung cho các mã lỗi đặc thù (429 retryAfterSeconds, 410 minAvailableCursor, 409 visibleFromMessageId). */
    public static final class ErrorDetails {
        @Nullable public Long retryAfterSeconds;
        @Nullable public Long minAvailableCursor;
        @Nullable public Long visibleFromMessageId;
    }
}
