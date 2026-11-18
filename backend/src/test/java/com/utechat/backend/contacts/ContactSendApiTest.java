package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;

/** Nghiệm thu mục 04: HTTP/JWT, PostgreSQL, đồng thời và nguyên tử lời mời/sự kiện. */
@Timeout(30)
class ContactSendApiTest extends ContactApiSupport {
    private JsonNode send(UUID actor, UUID target, int status) throws Exception {
        return call(actor, post("/api/contact-requests"), Map.of("addresseeId", target.toString()), status);
    }

    private void noWrites() {
        assertEquals(0, requests());
        assertEquals(0, events());
    }

    @Test void missingJwtIsUnauthorized() throws Exception {
        code(send(null, b, 401), "SESSION_EXPIRED");
        noWrites();
    }

    @Test void invalidJwtIsUnauthorized() throws Exception {
        code(call(null, post("/api/contact-requests").header("Authorization", "Bearer invalid-token"),
                Map.of("addresseeId", b.toString()), 401), "SESSION_EXPIRED");
        noWrites();
    }

    /** UUID thiếu, sai hoặc dạng rút gọn phải là lỗi body 400, không bị coi là ID hợp lệ. */
    @Test void invalidAddresseeIdsAreValidationErrors() throws Exception {
        for (String id : new String[]{null, "", " ", "not-a-uuid", "1-1-1-1-1"}) {
            Map<String, Object> body = new HashMap<>();
            body.put("addresseeId", id);
            code(call(a, post("/api/contact-requests"), body, 400), "VALIDATION");
        }
        code(call(a, post("/api/contact-requests"), Map.of(), 400), "VALIDATION");
        noWrites();
    }

    @Test void malformedJsonIsValidationError() throws Exception {
        code(call(a, post("/api/contact-requests").contentType(MediaType.APPLICATION_JSON).content("{"), null, 400), "VALIDATION");
        noWrites();
    }

    @Test void missingBodyIsValidationError() throws Exception {
        code(call(a, post("/api/contact-requests").contentType(MediaType.APPLICATION_JSON), null, 400), "VALIDATION");
        noWrites();
    }

    @Test void cannotSendToSelf() throws Exception { code(send(a, a, 400), "SELF"); noWrites(); }

    @Test void unknownTargetIsNotFound() throws Exception { code(send(a, UUID.randomUUID(), 404), "NOT_FOUND"); noWrites(); }

    @Test void inactiveTargetCannotReceiveRequest() throws Exception {
        for (String status : new String[]{"LOCKED", "DELETED"}) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, b);
            code(send(a, b, 404), "NOT_FOUND");
        }
        noWrites();
    }

    @Test void callerBlockPreventsSend() throws Exception {
        contact(a, b, false, true);
        code(send(a, b, 403), "BLOCKED");
        noWrites();
    }

    @Test void targetBlockPreventsSend() throws Exception {
        contact(b, a, false, true);
        code(send(a, b, 403), "BLOCKED");
        noWrites();
    }

    @Test void acceptedFriendsCannotSendAgain() throws Exception {
        contact(a, b, true, false); contact(b, a, true, false);
        code(send(a, b, 409), "ALREADY_FRIENDS");
        noWrites();
    }

    /** Quyền nhận tin người lạ và discoverable không phải quyền nhận lời mời khi đã biết UUID. */
    @Test void messagingPrivacyDoesNotDisableInvitationByKnownId() throws Exception {
        db.jdbc.update("UPDATE users SET discoverable=FALSE,allow_messages_from_non_contacts=FALSE WHERE id=?", b);
        assertEquals("PENDING", send(a, b, 201).path("status").asText());
    }

    @Test void creationReturnsPendingAndWritesExactlyTwoMinimalEvents() throws Exception {
        JsonNode result = send(a, b, 201);
        Set<String> fields = new HashSet<>(); result.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "requester", "addressee", "status", "createdAt", "respondedAt"), fields);
        assertEquals("PENDING", result.path("status").asText());
        assertTrue(result.path("respondedAt").isNull());
        assertDoesNotThrow(() -> Instant.parse(result.path("createdAt").asText()));
        assertEquals(a.toString(), result.path("requester").path("id").asText());
        assertEquals(b.toString(), result.path("addressee").path("id").asText());
        assertEquals(3, result.path("requester").size());
        assertEquals(3, result.path("addressee").size());
        assertEquals(1, requests());
        assertEquals(2, events());
        assertEquals(Set.of(a, b), new HashSet<>(db.jdbc.queryForList("SELECT user_id FROM sync_events", UUID.class)));
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
        for (var event : db.jdbc.queryForList("SELECT event_type,entity_type,entity_id,payload_json::text AS payload FROM sync_events")) {
            assertEquals("CONTACT_REQUEST_CREATED", event.get("event_type"));
            assertEquals("CONTACT_REQUEST", event.get("entity_type"));
            assertEquals(result.path("id").asText(), event.get("entity_id"));
            assertEquals(result, json.readTree((String) event.get("payload")));
            assertFalse(result.path("requester").has("phone"));
            assertFalse(result.path("addressee").has("phone"));
        }
    }

    @Test void sameDirectionDuplicateReturnsExistingIdWithoutNewEvents() throws Exception {
        String id = send(a, b, 201).path("id").asText();
        JsonNode result = send(a, b, 409);
        code(result, "REQUEST_ALREADY_PENDING");
        assertEquals(id, result.path("error").path("details").path("existingRequestId").asText());
        assertEquals("OUTGOING", result.path("error").path("details").path("direction").asText());
        assertEquals(1, requests()); assertEquals(2, events());
    }

    @Test void oppositeDirectionDuplicateDoesNotAutomaticallyAccept() throws Exception {
        String id = send(b, a, 201).path("id").asText();
        JsonNode result = send(a, b, 409);
        code(result, "REQUEST_ALREADY_PENDING");
        assertEquals(id, result.path("error").path("details").path("existingRequestId").asText());
        assertEquals("INCOMING", result.path("error").path("details").path("direction").asText());
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM contact_requests", String.class));
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
        assertEquals(1, requests()); assertEquals(2, events());
    }

    @Test void concurrentSameDirectionProducesOnePendingRequest() throws Exception { concurrent(false); }
    @Test void concurrentOppositeDirectionsProduceOnePendingRequest() throws Exception { concurrent(true); }

    private record Outcome(int status, JsonNode body) { }

    /** Hai HTTP request đi qua Bearer/filter thật cùng lúc; không chỉ gọi hai mock hoặc hai lần tuần tự. */
    private void concurrent(boolean opposite) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                UUID sender = opposite && i == 1 ? b : a;
                UUID target = sender.equals(a) ? b : a;
                jobs.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var response = mvc.perform(post("/api/contact-requests").header("Authorization", "Bearer " + token(sender))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("addresseeId", target.toString()))))
                            .andReturn().getResponse();
                    return new Outcome(response.getStatus(), json.readTree(response.getContentAsByteArray()));
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var job : jobs) outcomes.add(job.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(result -> result.status() == 201).count());
            assertEquals(1, outcomes.stream().filter(result -> result.status() == 409).count());
            var created = outcomes.stream().filter(result -> result.status() == 201).findFirst().orElseThrow().body();
            var conflict = outcomes.stream().filter(result -> result.status() == 409).findFirst().orElseThrow().body();
            code(conflict, "REQUEST_ALREADY_PENDING");
            assertEquals(created.path("id").asText(), conflict.path("error").path("details").path("existingRequestId").asText());
            assertEquals(opposite ? "INCOMING" : "OUTGOING", conflict.path("error").path("details").path("direction").asText());
            assertEquals(1, requests()); assertEquals(2, events());
            assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM contact_requests", String.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Chặn sự kiện cho bên thứ hai bằng CHECK thật; lời mời và sự kiện bên thứ nhất phải cùng rollback. */
    @Test void secondRecipientEventFailureRollsBackAndRetryIsClean() throws Exception {
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_reject_second_recipient CHECK (user_id <> '" + b + "'::uuid)");
        JsonNode failed = send(a, b, 500);
        code(failed, "INTERNAL");
        assertFalse(failed.path("error").has("details"));
        noWrites();
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_reject_second_recipient");
        assertEquals("PENDING", send(a, b, 201).path("status").asText());
        assertEquals(1, requests()); assertEquals(2, events());
    }

    @Test void bodyAndQueryCannotForgeRequesterIdentity() throws Exception {
        JsonNode result = call(a, post("/api/contact-requests").param("userId", c.toString()),
                Map.of("addresseeId", b.toString(), "requesterId", c.toString()), 201);
        assertEquals(a.toString(), result.path("requester").path("id").asText());
        assertEquals(a, db.jdbc.queryForObject("SELECT requester_id FROM contact_requests", UUID.class));
    }

    @Test void uppercaseCanonicalUuidIsAccepted() throws Exception {
        JsonNode result = call(a, post("/api/contact-requests"), Map.of("addresseeId", b.toString().toUpperCase(Locale.ROOT)), 201);
        assertEquals(b.toString(), result.path("addressee").path("id").asText());
    }
}
