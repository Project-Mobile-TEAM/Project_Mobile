package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mục 05: danh sách đi qua JWT/filter thật, truy vấn PostgreSQL và trả hồ sơ tối thiểu. */
class ContactRequestListApiTest extends ContactApiSupport {
    /** Query chỉ có chiều/trạng thái; danh tính luôn lấy từ Bearer của fixture. */
    private JsonNode list(UUID actor, String status, String type, int expected) throws Exception {
        var request = get("/api/contact-requests");
        if (status != null) request.param("status", status);
        if (type != null) request.param("type", type);
        return call(actor, request, null, expected);
    }

    private Set<String> ids(JsonNode response) {
        Set<String> result = new HashSet<>();
        response.forEach(row -> result.add(row.path("id").asText()));
        return result;
    }

    /** Seed trạng thái lịch sử bằng SQL, không gọi API reject/cancel chưa thuộc phạm vi. */
    private UUID historical(UUID sender, UUID recipient, String state) {
        UUID key = pending(sender, recipient);
        db.jdbc.update("UPDATE contact_requests SET status=?,responded_at=now() WHERE id=?", state, key);
        return key;
    }

    @Test void missingJwtIsUnauthorized() throws Exception {
        code(list(null, null, null, 401), "SESSION_EXPIRED");
    }

    @Test void invalidJwtIsUnauthorized() throws Exception {
        code(call(null, get("/api/contact-requests").header("Authorization", "Bearer invalid"), null, 401), "SESSION_EXPIRED");
    }

    @Test void defaultReturnsOnlyOwnPendingBothDirections() throws Exception {
        historical(c, a, "ACCEPTED");
        UUID incoming = pending(b, a), outgoing = pending(a, c);
        pending(b, c);
        assertEquals(Set.of(incoming.toString(), outgoing.toString()), ids(list(a, null, null, 200)));
    }

    @Test void incomingFiltersByAuthenticatedRecipient() throws Exception {
        UUID incoming = pending(b, a);
        pending(a, c); pending(b, c);
        assertEquals(Set.of(incoming.toString()), ids(list(a, "PENDING", "incoming", 200)));
    }

    @Test void outgoingFiltersByAuthenticatedRequester() throws Exception {
        UUID outgoing = pending(a, b);
        pending(c, a); pending(b, c);
        assertEquals(Set.of(outgoing.toString()), ids(list(a, "PENDING", "outgoing", 200)));
    }

    @Test void everySupportedStatusIsFilteredExactly() throws Exception {
        Map<String, UUID> keys = new HashMap<>();
        for (String state : List.of("ACCEPTED", "REJECTED", "CANCELED")) keys.put(state, historical(a, b, state));
        keys.put("PENDING", pending(a, b));
        for (var entry : keys.entrySet()) {
            JsonNode result = list(a, entry.getKey(), null, 200);
            assertEquals(Set.of(entry.getValue().toString()), ids(result));
            assertEquals(entry.getKey(), result.get(0).path("status").asText());
        }
    }

    @Test void statusAndDirectionAreCombined() throws Exception {
        UUID key = historical(b, a, "REJECTED");
        historical(a, c, "REJECTED");
        pending(c, a);
        assertEquals(Set.of(key.toString()), ids(list(a, "REJECTED", "incoming", 200)));
    }

    @Test void unrelatedAccountCannotReadOthersRequests() throws Exception {
        historical(b, a, "ACCEPTED"); pending(a, b);
        assertTrue(list(c, null, null, 200).isEmpty());
        assertTrue(list(c, "ACCEPTED", null, 200).isEmpty());
    }

    @Test void queryUserIdCannotSelectAnotherAccountsList() throws Exception {
        pending(a, b);
        JsonNode result = call(c, get("/api/contact-requests").param("userId", a.toString())
                .param("requesterId", a.toString()).param("addresseeId", b.toString()), null, 200);
        assertTrue(result.isArray());
        assertTrue(result.isEmpty());
    }

    @Test void payloadHasExactRequestAndMinimalProfileFields() throws Exception {
        UUID key = pending(b, a);
        JsonNode row = list(a, null, null, 200).get(0);
        Set<String> fields = new HashSet<>(); row.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "requester", "addressee", "status", "createdAt", "respondedAt"), fields);
        assertEquals(key.toString(), row.path("id").asText());
        assertEquals("Người B", row.path("requester").path("displayName").asText());
        assertTrue(row.path("respondedAt").isNull());
        for (String field : List.of("requester", "addressee")) {
            fields.clear(); row.path(field).fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "displayName", "avatarUrl"), fields);
        }
    }

    @Test void privacyDoesNotHideAnExistingParticipantsInvitation() throws Exception {
        UUID key = pending(b, a);
        db.jdbc.update("UPDATE users SET discoverable=FALSE,allow_messages_from_non_contacts=FALSE WHERE id=?", b);
        assertEquals(Set.of(key.toString()), ids(list(a, null, null, 200)));
    }

    @Test void inactiveParticipantsAreExcluded() throws Exception {
        pending(b, a);
        for (String state : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", state, b);
            assertTrue(list(a, null, null, 200).isEmpty());
        }
    }

    @Test void invalidStatusIncludingSqlTextIsRejected() throws Exception {
        for (String state : List.of("pending", "ALL", "PENDING' OR TRUE --", " PENDING"))
            code(list(a, state, null, 400), "VALIDATION");
    }

    @Test void invalidDirectionIncludingSqlTextIsRejected() throws Exception {
        for (String type : List.of("INCOMING", "both", "incoming OR TRUE", " outgoing"))
            code(list(a, null, type, 400), "VALIDATION");
    }

    @Test void blankFiltersKeepDraftDefaultPendingBothDirections() throws Exception {
        UUID key = pending(a, b);
        assertEquals(Set.of(key.toString()), ids(list(a, " ", "", 200)));
    }

    @Test void newestFirstHasStableIdTieBreaker() throws Exception {
        UUID first = historical(a, b, "ACCEPTED"), second = historical(c, a, "ACCEPTED");
        db.jdbc.update("UPDATE contact_requests SET created_at='2026-01-01T00:00:00Z' WHERE id IN (?,?)", first, second);
        // UUID PostgreSQL sắp theo byte không dấu; so chuỗi canonical tương đương thứ tự đó.
        String larger = first.toString().compareTo(second.toString()) > 0 ? first.toString() : second.toString();
        assertEquals(larger, list(a, "ACCEPTED", null, 200).get(0).path("id").asText());
        db.jdbc.update("UPDATE contact_requests SET created_at='2026-01-02T00:00:00Z' WHERE id=?", first);
        assertEquals(first.toString(), list(a, "ACCEPTED", null, 200).get(0).path("id").asText());
    }

    @Test void emptyListIsJsonArrayAndReadingDoesNotWrite() throws Exception {
        JsonNode empty = list(a, null, null, 200);
        assertTrue(empty.isArray()); assertTrue(empty.isEmpty());
        UUID key = pending(a, b);
        for (int i = 0; i < 3; i++) assertEquals(Set.of(key.toString()), ids(list(a, null, null, 200)));
        assertEquals(1, requests()); assertEquals(0, events());
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
    }
}
