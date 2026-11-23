package com.utechat.data.remote.model;

import androidx.annotation.Nullable;
import java.util.List;

/**
 * Tập hợp DTO Gson cho REST API và WebSocket cuộc gọi thoại WebRTC 1-1 (Plan 06-02 / 06-06).
 * Tách biệt hoàn toàn khỏi UI state và không bao giờ mang bí mật tĩnh COTURN_SHARED_SECRET.
 */
public final class CallDtos {
    private CallDtos() { }

    /** Yêu cầu khởi tạo cuộc gọi thoại 1-1: POST /api/calls. */
    public static final class StartCallRequest {
        public final String conversationId;
        public final String media;

        public StartCallRequest(String conversationId, String media) {
            this.conversationId = conversationId;
            this.media = media;
        }
    }

    /** Một máy chủ STUN/TURN tạm thời do backend cấp qua HMAC-SHA1. */
    public static final class IceServerDto {
        public List<String> urls;
        @Nullable public String username;
        @Nullable public String credential;

        public IceServerDto() { }

        public IceServerDto(List<String> urls, @Nullable String username, @Nullable String credential) {
            this.urls = urls;
            this.username = username;
            this.credential = credential;
        }
    }

    /** Phản hồi khởi tạo cuộc gọi (201 Created) kèm hạn đổ chuông expiresAt và danh sách ICE server. */
    public static final class StartCallResponse {
        public String callId;
        public String state;
        @Nullable public String expiresAt;
        public List<IceServerDto> iceServers;

        public StartCallResponse() { }

        public StartCallResponse(
                String callId,
                String state,
                @Nullable String expiresAt,
                List<IceServerDto> iceServers) {
            this.callId = callId;
            this.state = state;
            this.expiresAt = expiresAt;
            this.iceServers = iceServers;
        }
    }

    /** Phản hồi chấp nhận cuộc gọi (200 OK) kèm danh sách ICE server cho phía người nhận. */
    public static final class AcceptCallResponse {
        public String callId;
        public String state;
        public List<IceServerDto> iceServers;

        public AcceptCallResponse() { }

        public AcceptCallResponse(String callId, String state, List<IceServerDto> iceServers) {
            this.callId = callId;
            this.state = state;
            this.iceServers = iceServers;
        }
    }

    /** Hồ sơ tối thiểu của người gọi gửi kèm trong sự kiện call.incoming / MISSED. */
    public static final class CallerInfoDto {
        public String id;
        @Nullable public String displayName;
        @Nullable public String avatarUrl;

        public CallerInfoDto() { }

        public CallerInfoDto(String id, @Nullable String displayName, @Nullable String avatarUrl) {
            this.id = id;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
        }
    }

    /** Payload chung cho các sự kiện vòng đời cuộc gọi (call.incoming, call.accepted, call.ended, call.handled_elsewhere). */
    public static final class CallEventPayload {
        public String callId;
        @Nullable public String conversationId;
        @Nullable public String state;
        @Nullable public String endReason;
        @Nullable public String media;
        @Nullable public CallerInfoDto caller;
        @Nullable public String handledByDeviceIdHash;
        @Nullable public String expiresAt;

        public CallEventPayload() { }
    }

    /** Payload báo hiệu WebRTC tạm thời (call.offer, call.answer, call.ice). */
    public static final class CallSignalingPayload {
        public String callId;
        @Nullable public String sdp;
        @Nullable public String candidate;
        @Nullable public String sdpMid;
        @Nullable public Integer sdpMLineIndex;

        public CallSignalingPayload() { }
    }
}
