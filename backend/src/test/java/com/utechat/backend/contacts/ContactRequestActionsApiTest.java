package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Mục 07: reject/cancel qua Bearer thật; PostgreSQL kiểm trạng thái, quyền và sự kiện nguyên tử. */
@Timeout(30)
class ContactRequestActionsApiTest extends ContactApiSupport {
    private MockHttpServletRequestBuilder route(boolean cancel, String key) {
        return cancel ? delete("/api/contact-requests/" + key) : post("/api/contact-requests/" + key + "/reject");
    }

    private JsonNode action(boolean cancel, UUID actor, UUID key, int expected) throws Exception {
        return call(actor, route(cancel, key.toString()), null, expected);
    }

    private String state(UUID key) { return db.jdbc.queryForObject("SELECT status FROM contact_requests WHERE id=?", String.class, key); }
    private int contacts() { return db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class); }

    private void unchanged(UUID key) {
        assertEquals("PENDING", state(key));
        assertNull(db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key));
        assertEquals(0, events()); assertEquals(0, contacts());
    }

    /** Hai sự kiện phải là toàn bộ DTO tối thiểu sau thao tác, đúng người nhận và không lộ SĐT. */
    private void closedEvents(UUID key, String expectedState) throws Exception {
        JsonNode dto = call(a, get("/api/contact-requests").param("status", expectedState), null, 200).get(0);
        assertEquals(expectedState, state(key)); assertEquals(key.toString(), dto.path("id").asText());
        assertDoesNotThrow(() -> Instant.parse(dto.path("respondedAt").asText()));
        assertEquals(6, dto.size());
        for (String name : List.of("requester", "addressee")) {
            Set<String> fields = new HashSet<>(); dto.path(name).fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "displayName", "avatarUrl"), fields);
        }
        var rows = db.jdbc.queryForList("SELECT user_id,event_type,entity_type,entity_id,payload_json::text AS payload FROM sync_events");
        assertEquals(2, rows.size());
        Set<UUID> owners = new HashSet<>();
        for (var row : rows) {
            owners.add((UUID) row.get("user_id"));
            assertEquals("CONTACT_REQUEST_UPDATED", row.get("event_type")); assertEquals("CONTACT_REQUEST", row.get("entity_type"));
            assertEquals(key.toString(), row.get("entity_id")); assertEquals(dto, json.readTree((String) row.get("payload")));
        }
        assertEquals(Set.of(a, b), owners); assertEquals(0, contacts());
    }

    @Test void missingJwtRejectsBothActions() throws Exception {
        UUID key = pending(a, b);
        for (boolean cancel : List.of(false, true)) code(action(cancel, null, key, 401), "SESSION_EXPIRED");
        unchanged(key);
    }

    @Test void invalidJwtRejectsBothActions() throws Exception {
        UUID key = pending(a, b);
        for (boolean cancel : List.of(false, true))
            code(call(null, route(cancel, key.toString()).header("Authorization", "Bearer invalid"), null, 401), "SESSION_EXPIRED");
        unchanged(key);
    }

    @Test void malformedAndUnknownIdsAreNotFound() throws Exception {
        for (boolean cancel : List.of(false, true)) for (String key : List.of("invalid", "1-1-1-1-1", UUID.randomUUID().toString()))
            code(call(cancel ? a : b, route(cancel, key), null, 404), "NOT_FOUND");
        assertEquals(0, contacts()); assertEquals(0, events());
    }

    @Test void senderCannotReject() throws Exception {
        UUID key = pending(a, b); code(action(false, a, key, 403), "FORBIDDEN"); unchanged(key);
    }

    @Test void recipientCannotCancel() throws Exception {
        UUID key = pending(a, b); code(action(true, b, key, 403), "FORBIDDEN"); unchanged(key);
    }

    @Test void thirdPartyCannotPerformEitherAction() throws Exception {
        UUID key = pending(a, b);
        for (boolean cancel : List.of(false, true)) code(action(cancel, c, key, 403), "FORBIDDEN");
        unchanged(key);
    }

    @Test void bodyAndQueryCannotForgeActor() throws Exception {
        UUID key = pending(a, b);
        for (boolean cancel : List.of(false, true)) code(call(c, route(cancel, key.toString()).param("userId", (cancel ? a : b).toString()),
                Map.of("actorId", (cancel ? a : b).toString(), "status", "ACCEPTED"), 403), "FORBIDDEN");
        unchanged(key);
    }

    @Test void recipientRejectsAndWritesMinimalEvents() throws Exception {
        UUID key = pending(a, b);
        assertEquals("REJECTED", action(false, b, key, 200).path("status").asText()); closedEvents(key, "REJECTED");
    }

    @Test void senderCancelsWithEmpty204AndWritesMinimalEvents() throws Exception {
        UUID key = pending(a, b);
        assertNull(action(true, a, key, 204)); closedEvents(key, "CANCELED");
    }

    @Test void repeatedRejectKeepsResponseTimeAndEventCount() throws Exception {
        UUID key = pending(a, b); JsonNode first = action(false, b, key, 200);
        assertEquals(first, action(false, b, key, 200)); closedEvents(key, "REJECTED");
        code(action(false, a, key, 403), "FORBIDDEN");
    }

    @Test void repeatedCancelKeepsResponseTimeAndEventCount() throws Exception {
        UUID key = pending(a, b); action(true, a, key, 204);
        Object first = db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key);
        assertNull(action(true, a, key, 204));
        assertEquals(first, db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key));
        closedEvents(key, "CANCELED"); code(action(true, b, key, 403), "FORBIDDEN");
    }

    @Test void rejectAfterCancelIsInvalidState() throws Exception {
        UUID key = pending(a, b); action(true, a, key, 204);
        code(action(false, b, key, 409), "INVALID_STATE"); closedEvents(key, "CANCELED");
    }

    @Test void cancelAfterRejectIsInvalidState() throws Exception {
        UUID key = pending(a, b); action(false, b, key, 200);
        code(action(true, a, key, 409), "INVALID_STATE"); closedEvents(key, "REJECTED");
    }

    @Test void acceptAfterRejectIsInvalidState() throws Exception {
        UUID key = pending(a, b); action(false, b, key, 200);
        code(call(b, post("/api/contact-requests/" + key + "/accept"), null, 409), "INVALID_STATE"); closedEvents(key, "REJECTED");
    }

    @Test void acceptAfterCancelIsInvalidState() throws Exception {
        UUID key = pending(a, b); action(true, a, key, 204);
        code(call(b, post("/api/contact-requests/" + key + "/accept"), null, 409), "INVALID_STATE"); closedEvents(key, "CANCELED");
    }

    @Test void neitherActionCanUndoAcceptedFriendship() throws Exception {
        UUID key = pending(a, b); call(b, post("/api/contact-requests/" + key + "/accept"), null, 200);
        code(action(false, b, key, 409), "INVALID_STATE"); code(action(true, a, key, 409), "INVALID_STATE");
        assertEquals("ACCEPTED", state(key)); assertEquals(2, contacts()); assertEquals(4, events());
    }

    @Test void blockDoesNotPreventParticipantFromRejecting() throws Exception {
        UUID key = pending(a, b); contact(a, b, false, true);
        action(false, b, key, 200); assertEquals("REJECTED", state(key)); assertEquals(2, events());
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts", Boolean.class));
    }

    @Test void blockDoesNotPreventParticipantFromCanceling() throws Exception {
        UUID key = pending(a, b); contact(b, a, false, true);
        action(true, a, key, 204); assertEquals("CANCELED", state(key)); assertEquals(2, events());
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts", Boolean.class));
    }

    @Test void inactiveOtherParticipantIsNotFound() throws Exception {
        UUID key = pending(a, b);
        db.jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?", a);
        code(action(false, b, key, 404), "NOT_FOUND");
        db.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", a);
        db.jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", b);
        code(action(true, a, key, 404), "NOT_FOUND"); unchanged(key);
    }

    /** CHECK tại người nhận sự kiện thứ hai phải hủy cả chuyển trạng thái và sự kiện thứ nhất. */
    private void rollback(boolean cancel) throws Exception {
        UUID key = pending(a, b);
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_action_event CHECK (user_id <> '" + b + "'::uuid)");
        code(action(cancel, cancel ? a : b, key, 500), "INTERNAL"); unchanged(key);
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_action_event");
        action(cancel, cancel ? a : b, key, cancel ? 204 : 200); closedEvents(key, cancel ? "CANCELED" : "REJECTED");
    }

    @Test void rejectEventFailureRollsBackAndRetryIsClean() throws Exception { rollback(false); }
    @Test void cancelEventFailureRollsBackAndRetryIsClean() throws Exception { rollback(true); }

    private record Outcome(int status, JsonNode body) { }

    /** Executor vẫn gửi Bearer qua filter thật, không gọi service trực tiếp hoặc giả authentication. */
    private Outcome threaded(UUID actor, MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + token(actor))).andReturn().getResponse();
        return new Outcome(response.getStatus(), response.getContentAsByteArray().length == 0 ? null : json.readTree(response.getContentAsByteArray()));
    }

    private List<Outcome> concurrently(UUID firstActor, MockHttpServletRequestBuilder first, UUID secondActor, MockHttpServletRequestBuilder second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> one = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(firstActor, first); });
            Future<Outcome> two = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(secondActor, second); });
            start.countDown(); return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentRepeatedRejectProducesOnlyTwoEvents() throws Exception {
        UUID key = pending(a, b); var results = concurrently(b, route(false, key.toString()), b, route(false, key.toString()));
        assertEquals(List.of(200, 200), results.stream().map(Outcome::status).toList());
        assertEquals(results.get(0).body(), results.get(1).body()); closedEvents(key, "REJECTED");
    }

    @Test void concurrentRepeatedCancelProducesOnlyTwoEvents() throws Exception {
        UUID key = pending(a, b); var results = concurrently(a, route(true, key.toString()), a, route(true, key.toString()));
        assertEquals(List.of(204, 204), results.stream().map(Outcome::status).toList()); closedEvents(key, "CANCELED");
    }

    /** Accept và đóng lời mời tranh cùng khóa cặp; chỉ một chuyển trạng thái được commit. */
    private void raceAccept(boolean cancel) throws Exception {
        UUID key = pending(a, b);
        var results = concurrently(b, post("/api/contact-requests/" + key + "/accept"), cancel ? a : b, route(cancel, key.toString()));
        assertEquals(1, results.stream().filter(result -> result.status() == 409).count());
        code(results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow().body(), "INVALID_STATE");
        if ("ACCEPTED".equals(state(key))) {
            assertEquals(200, results.get(0).status()); assertEquals(2, contacts()); assertEquals(4, events());
        } else {
            assertEquals(cancel ? "CANCELED" : "REJECTED", state(key));
            assertEquals(cancel ? 204 : 200, results.get(1).status()); closedEvents(key, state(key));
        }
    }

    @Test void acceptAndRejectHaveOneWinner() throws Exception { raceAccept(false); }
    @Test void acceptAndCancelHaveOneWinner() throws Exception { raceAccept(true); }

    @Test void rejectAndCancelHaveOneWinner() throws Exception {
        UUID key = pending(a, b); var results = concurrently(b, route(false, key.toString()), a, route(true, key.toString()));
        assertEquals(1, results.stream().filter(result -> result.status() == 409).count());
        code(results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow().body(), "INVALID_STATE");
        assertTrue(Set.of("REJECTED", "CANCELED").contains(state(key))); closedEvents(key, state(key));
    }

    @Test void sendRejectResendAndCancelUpdateBothLists() throws Exception {
        JsonNode first = call(a, post("/api/contact-requests"), Map.of("addresseeId", b.toString()), 201);
        UUID key = UUID.fromString(first.path("id").asText()); action(false, b, key, 200);
        assertTrue(call(a, get("/api/contact-requests"), null, 200).isEmpty());
        JsonNode second = call(b, post("/api/contact-requests"), Map.of("addresseeId", a.toString()), 201);
        action(true, b, UUID.fromString(second.path("id").asText()), 204);
        for (UUID actor : List.of(a, b)) {
            assertTrue(call(actor, get("/api/contact-requests"), null, 200).isEmpty());
            assertEquals(1, call(actor, get("/api/contact-requests").param("status", "REJECTED"), null, 200).size());
            assertEquals(1, call(actor, get("/api/contact-requests").param("status", "CANCELED"), null, 200).size());
        }
        assertEquals("REJECTED", state(key)); assertEquals(0, contacts()); assertEquals(8, events());
    }
}
