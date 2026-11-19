package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Nghiệm thu mục 03 bằng HTTP/JWT và PostgreSQL thật; không gọi API quan hệ khác. */
class ContactLookupApiTest extends ContactApiSupport {
    private JsonNode lookup(UUID actor, String phone, int status) throws Exception {
        var request = get("/api/users/lookup");
        if (phone != null) request.param("phone", phone);
        return call(actor, request, null, status);
    }

    @Test void missingJwtIsUnauthorized() throws Exception {
        code(lookup(null, PHONE_B, 401), "SESSION_EXPIRED");
    }

    @Test void invalidJwtIsUnauthorized() throws Exception {
        code(call(null, get("/api/users/lookup").param("phone", PHONE_B)
                .header("Authorization", "Bearer invalid-token"), null, 401), "SESSION_EXPIRED");
    }

    @Test void missingPhoneIsPhoneInvalid() throws Exception { code(lookup(a, null, 400), "PHONE_INVALID"); }

    /** Backend yêu cầu E.164, không nhận số Việt Nam chưa chuẩn hóa hoặc đầu vào có ký tự thừa. */
    @Test void malformedPhonesAreRejected() throws Exception {
        for (String phone : new String[]{"", "0901111111", "+084901111111", "+1234567", "+1234567890123456",
                "++84901111111", "+84901111111x", " +84901111111", "+84 901111111"}) {
            code(lookup(a, phone, 400), "PHONE_INVALID");
        }
    }

    @Test void e164MinimumAndMaximumLengthsAreSupported() throws Exception {
        for (String phone : new String[]{"+12345678", "+123456789012345"}) {
            db.jdbc.update("UPDATE users SET phone_e164=? WHERE id=?", phone, c);
            assertEquals(c.toString(), lookup(a, phone, 200).path("id").asText());
        }
    }

    @Test void selfLookupIsRejected() throws Exception { code(lookup(a, PHONE_A, 400), "SELF"); }

    @Test void unknownPhoneIsNotFound() throws Exception { code(lookup(a, "+84909999999", 404), "NOT_FOUND"); }

    /** Không tìm gần đúng hoặc theo prefix, kể cả prefix còn đúng cấu trúc E.164. */
    @Test void lookupDoesNotMatchPhonePrefix() throws Exception { code(lookup(a, "+84901111", 404), "NOT_FOUND"); }

    @Test void privateProfileIsHiddenLikeUnknownPhone() throws Exception {
        db.jdbc.update("UPDATE users SET discoverable=FALSE WHERE id=?", b);
        assertEquals(lookup(a, "+84909999999", 404), lookup(a, PHONE_B, 404));
    }

    @Test void lockedAndDeletedTargetsAreHidden() throws Exception {
        for (String status : new String[]{"LOCKED", "DELETED"}) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, b);
            code(lookup(a, PHONE_B, 404), "NOT_FOUND");
        }
    }

    @Test void blockByCallerHidesTarget() throws Exception {
        contact(a, b, false, true);
        code(lookup(a, PHONE_B, 404), "NOT_FOUND");
    }

    @Test void blockByTargetHidesTarget() throws Exception {
        contact(b, a, false, true);
        code(lookup(a, PHONE_B, 404), "NOT_FOUND");
    }

    /** JSON chỉ có dữ liệu được phép trả; không lộ SĐT, password hoặc cài đặt riêng tư. */
    @Test void strangerPayloadUsesExactPublicContract() throws Exception {
        JsonNode result = lookup(a, PHONE_B, 200);
        Set<String> fields = new HashSet<>();
        result.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "displayName", "avatarUrl", "isContact", "hasPendingRequest",
                "relationshipStatus", "pendingRequestId", "pendingRequestDirection"), fields);
        assertEquals(b.toString(), result.path("id").asText());
        assertEquals("Người B", result.path("displayName").asText());
        assertFalse(result.path("isContact").asBoolean());
        assertFalse(result.path("hasPendingRequest").asBoolean());
        assertEquals("STRANGER", result.path("relationshipStatus").asText());
        assertTrue(result.path("pendingRequestId").isNull());
        assertTrue(result.path("pendingRequestDirection").isNull());
    }

    @Test void reciprocalFriendsHaveFriendStatus() throws Exception {
        contact(a, b, true, false); contact(b, a, true, false);
        JsonNode result = lookup(a, PHONE_B, 200);
        assertTrue(result.path("isContact").asBoolean());
        assertEquals("FRIEND", result.path("relationshipStatus").asText());
        assertFalse(result.path("hasPendingRequest").asBoolean());
    }

    @Test void incomingPendingReturnsIdAndDirection() throws Exception {
        UUID request = pending(b, a);
        JsonNode result = lookup(a, PHONE_B, 200);
        assertEquals("INCOMING_PENDING", result.path("relationshipStatus").asText());
        assertEquals("INCOMING", result.path("pendingRequestDirection").asText());
        assertEquals(request.toString(), result.path("pendingRequestId").asText());
        assertTrue(result.path("hasPendingRequest").asBoolean());
        assertFalse(result.path("isContact").asBoolean());
    }

    @Test void outgoingPendingReturnsIdAndDirection() throws Exception {
        UUID request = pending(a, b);
        JsonNode result = lookup(a, PHONE_B, 200);
        assertEquals("OUTGOING_PENDING", result.path("relationshipStatus").asText());
        assertEquals("OUTGOING", result.path("pendingRequestDirection").asText());
        assertEquals(request.toString(), result.path("pendingRequestId").asText());
    }

    @Test void eleventhValidLookupIsRateLimitedWithRetryDetails() throws Exception {
        for (int i = 0; i < 10; i++) lookup(a, PHONE_B, 200);
        JsonNode result = lookup(a, PHONE_B, 429);
        code(result, "RATE_LIMITED");
        assertTrue(result.path("error").path("details").path("retryAfterSeconds").isIntegralNumber());
        assertEquals(60, result.path("error").path("details").path("retryAfterSeconds").asInt());
    }

    @Test void rateLimitIsIsolatedByJwtActor() throws Exception {
        for (int i = 0; i < 10; i++) lookup(a, PHONE_B, 200);
        lookup(a, PHONE_B, 429);
        assertEquals(b.toString(), lookup(c, PHONE_B, 200).path("id").asText());
    }

    /** Tra cứu hợp lệ nhưng không tìm thấy vẫn tiêu thụ lượt, tránh bỏ qua giới hạn khi dò SĐT. */
    @Test void unsuccessfulValidLookupsStillCountTowardLimit() throws Exception {
        for (int i = 0; i < 10; i++) lookup(a, "+84909999999", 404);
        code(lookup(a, "+84909999999", 429), "RATE_LIMITED");
    }

    @Test void malformedPhonesDoNotConsumeValidLookupBudget() throws Exception {
        for (int i = 0; i < 12; i++) lookup(a, "invalid", 400);
        for (int i = 0; i < 10; i++) lookup(a, PHONE_B, 200);
    }

    @Test void queryCannotOverrideJwtIdentity() throws Exception {
        JsonNode result = call(a, get("/api/users/lookup").param("phone", PHONE_B).param("userId", b.toString()), null, 200);
        assertEquals("STRANGER", result.path("relationshipStatus").asText());
    }

    @Test void lookupDoesNotWriteRelationshipsRequestsOrEvents() throws Exception {
        UUID request = pending(a, b);
        lookup(a, PHONE_B, 200);
        assertEquals(1, requests());
        assertEquals(request, db.jdbc.queryForObject("SELECT id FROM contact_requests", UUID.class));
        assertEquals(0, events());
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
    }
}
