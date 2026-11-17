package com.utechat.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Hợp đồng JSON danh bạ; hồ sơ trong lời mời không tiết lộ số điện thoại. */
public final class ContactDtos {
    private ContactDtos() { }

    /** Hồ sơ tối thiểu trong lời mời: không mang SĐT, mật khẩu hoặc cài đặt riêng tư. */
    public record UserSummaryDto(String id, String displayName, String avatarUrl) { }

    /** Giữ tên isContact trong JSON; ID và chiều lời mời giúp app hiện đúng nút thao tác. */
    public record UserLookupResponse(String id, String displayName, String avatarUrl,
            @JsonProperty("isContact") boolean isContact, boolean hasPendingRequest,
            String relationshipStatus, String pendingRequestId, String pendingRequestDirection) { }

    /** Client chỉ chỉ định người nhận; người gửi phải được server lấy từ JWT. */
    public record SendContactRequestBody(String addresseeId) { }

    /** Thời điểm dùng chuỗi ISO-8601 theo hợp đồng; respondedAt chưa có khi đang PENDING. */
    public record ContactRequestDto(String id, UserSummaryDto requester, UserSummaryDto addressee,
            String status, String createdAt, String respondedAt) { }

    /** Danh sách bạn bè/chặn có cấu trúc riêng; service quyết định có được trả phone hay không. */
    public record ContactDto(String id, String phone, String displayName, String avatarUrl,
            boolean blocked, String createdAt) { }
}
