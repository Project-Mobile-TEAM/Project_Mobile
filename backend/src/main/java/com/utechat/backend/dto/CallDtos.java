package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Tập hợp DTO bất biến (Java 17 record) cho toàn bộ API và sự kiện WebSocket cuộc gọi thoại WebRTC 1-1 (Phase 6).
 * Giữ tương thích ngược 100% với cấu trúc { callId, state, endReason } của SyncEventApplier từ Phase 5.
 */
public final class CallDtos {
    private CallDtos() {
    }

    /** Yêu cầu khởi tạo cuộc gọi thoại 1-1: chỉ hỗ trợ media = "VOICE". */
    public record StartCallRequest(
            String conversationId,
            String media) {
    }

    /** Cấu hình một ICE server (STUN không cần credential; TURN/TURNS kèm username/credential HMAC-SHA1 có hạn). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IceServerDto(
            List<String> urls,
            String username,
            String credential) {
    }

    /** Kết quả 201 Created của POST /api/calls kèm hạn chót đổ chuông (45s) và danh sách ICE servers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartCallResponse(
            String callId,
            String state,
            String expiresAt,
            List<IceServerDto> iceServers) {
    }

    /** Kết quả 200 OK của POST /api/calls/{id}/accept kèm danh sách ICE servers cấp mới cho người nhận. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptCallResponse(
            String callId,
            String state,
            List<IceServerDto> iceServers) {
    }

    /** Thông tin hiển thị tối thiểu của người gọi đính kèm trong sự kiện call.incoming. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallerInfoDto(
            String id,
            String displayName,
            String avatarUrl) {
    }

    /**
     * Payload ghi vào bảng sync_events (event_type = CALL_UPDATED) và phát qua WebSocket:
     * luôn bảo toàn bộ ba { callId, state, endReason } để SyncEventApplier cũ vẫn đọc được an toàn.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallUpdatedEventPayload(
            String callId,
            String conversationId,
            String state,
            String endReason,
            String media,
            String wsType,
            CallerInfoDto caller,
            String handledByDeviceIdHash,
            String expiresAt) {
    }
}
