package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.ContactDtos.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Kiểm hợp đồng JSON trên DTO thật; bổ sung nhầm trường riêng tư sẽ làm test thất bại. */
class ContactDtosTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UserSummaryDto sender = new UserSummaryDto("sender", "Người gửi", null);
    private final UserSummaryDto recipient = new UserSummaryDto("recipient", "Người nhận", "avatar.png");
    private static final String CREATED = "2026-11-17T10:00:00Z";

    /** Lấy tập tên trường để phát hiện cả trường bị thiếu và trường vô tình tiết lộ thêm. */
    private Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode encode(Object value) throws Exception {
        return json.readTree(json.writeValueAsString(value));
    }

    /** Hồ sơ lời mời chỉ có ba trường công khai, kể cả khi avatar chưa có. */
    @Test void userSummaryExposesOnlyIdNameAndAvatar() throws Exception {
        JsonNode body = encode(sender);
        assertEquals(Set.of("id", "displayName", "avatarUrl"), fields(body));
        assertEquals("Người gửi", body.path("displayName").asText());
        assertTrue(body.path("avatarUrl").isNull());
    }

    /** false phải còn trong JSON; không đổi isContact thành contact hoặc tên snake_case. */
    @Test void lookupUsesExactCamelCaseKeysAndKeepsFalseFlags() throws Exception {
        var dto = new UserLookupResponse("target", "Tên", null, false, false, "STRANGER", null, null);
        JsonNode body = encode(dto);
        assertEquals(Set.of("id", "displayName", "avatarUrl", "isContact", "hasPendingRequest",
                "relationshipStatus", "pendingRequestId", "pendingRequestDirection"), fields(body));
        assertTrue(body.get("isContact").isBoolean());
        assertFalse(body.get("isContact").booleanValue());
        assertFalse(body.get("hasPendingRequest").booleanValue());
        assertTrue(body.get("pendingRequestId").isNull());
        assertEquals(dto, json.treeToValue(body, UserLookupResponse.class));
    }

    /** Quan hệ bạn bè không bị nhầm thành trạng thái lời mời đang chờ. */
    @Test void friendLookupRoundTripsWithoutPendingRequest() throws Exception {
        var dto = new UserLookupResponse("target", "Bạn", "avatar.png", true, false, "FRIEND", null, null);
        JsonNode body = encode(dto);
        assertTrue(body.path("isContact").asBoolean());
        assertEquals("FRIEND", body.path("relationshipStatus").asText());
        assertEquals(dto, json.treeToValue(body, UserLookupResponse.class));
    }

    /** ID và chiều incoming phải đi cùng kết quả để app có thể gọi accept/reject đúng lời mời. */
    @Test void incomingLookupPreservesRequestIdAndDirection() throws Exception {
        var dto = new UserLookupResponse("target", "Tên", null, false, true, "INCOMING_PENDING", "request-1", "INCOMING");
        JsonNode body = encode(dto);
        assertTrue(body.path("hasPendingRequest").asBoolean());
        assertEquals("request-1", body.path("pendingRequestId").asText());
        assertEquals("INCOMING", body.path("pendingRequestDirection").asText());
        assertEquals(dto, json.treeToValue(body, UserLookupResponse.class));
    }

    /** Chiều outgoing không bị đổi tên thành direction hoặc chiều incoming. */
    @Test void outgoingLookupPreservesRequestIdAndDirection() throws Exception {
        var dto = new UserLookupResponse("target", "Tên", null, false, true, "OUTGOING_PENDING", "request-2", "OUTGOING");
        JsonNode body = encode(dto);
        assertEquals("OUTGOING_PENDING", body.path("relationshipStatus").asText());
        assertEquals("request-2", body.path("pendingRequestId").asText());
        assertEquals("OUTGOING", body.path("pendingRequestDirection").asText());
        assertEquals(dto, json.treeToValue(body, UserLookupResponse.class));
    }

    /** Kiểm cả hai hồ sơ lồng nhau để không lộ phone qua requester hoặc addressee. */
    @Test void pendingRequestContainsOnlyMinimalProfiles() throws Exception {
        var dto = new ContactRequestDto("request-1", sender, recipient, "PENDING", CREATED, null);
        JsonNode body = encode(dto);
        assertEquals(Set.of("id", "requester", "addressee", "status", "createdAt", "respondedAt"), fields(body));
        for (String side : List.of("requester", "addressee"))
            assertEquals(Set.of("id", "displayName", "avatarUrl"), fields(body.get(side)));
        assertEquals("PENDING", body.path("status").asText());
        assertTrue(body.path("respondedAt").isNull());
        assertEquals(dto, json.treeToValue(body, ContactRequestDto.class));
    }

    /** Không tự đổi múi giờ hoặc mất thời điểm phản hồi khi mã hóa và đọc lại. */
    @Test void answeredRequestPreservesTimestamps() throws Exception {
        var dto = new ContactRequestDto("request-1", sender, recipient, "ACCEPTED", CREATED, "2026-11-17T17:01:00+07:00");
        JsonNode body = encode(dto);
        assertEquals(CREATED, body.path("createdAt").asText());
        assertEquals(dto.respondedAt(), body.path("respondedAt").asText());
        assertEquals(dto, json.treeToValue(body, ContactRequestDto.class));
    }

    /** Body gửi lời mời chỉ gồm người nhận, không yêu cầu client khai danh tính người gửi. */
    @Test void sendBodyRoundTripsAddresseeId() throws Exception {
        var dto = json.readValue("{\"addresseeId\":\"recipient\"}", SendContactRequestBody.class);
        assertEquals("recipient", dto.addresseeId());
        assertEquals(Set.of("addresseeId"), fields(encode(dto)));
    }

    /** phone nullable cho trường hợp không được trả số; blocked luôn giữ kiểu boolean. */
    @Test void contactListPayloadSupportsFriendAndBlockedProfiles() throws Exception {
        for (var dto : List.of(new ContactDto("friend", "+84901111111", "Bạn", null, false, CREATED),
                new ContactDto("blocked", null, "Người bị chặn", null, true, CREATED))) {
            JsonNode body = encode(dto);
            assertEquals(Set.of("id", "phone", "displayName", "avatarUrl", "blocked", "createdAt"), fields(body));
            assertTrue(body.get("blocked").isBoolean());
            assertEquals(dto, json.treeToValue(body, ContactDto.class));
        }
    }
}
