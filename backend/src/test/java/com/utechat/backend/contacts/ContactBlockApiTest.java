package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Mục 09: mọi thao tác block/unblock và race đều qua HTTP/JWT thật, không dùng fixture block thay API. */
@Timeout(30)
class ContactBlockApiTest extends ContactApiSupport {
    private MockHttpServletRequestBuilder route(boolean unblock, UUID target) {
        return unblock ? delete("/api/contacts/" + target + "/block") : post("/api/contacts/" + target + "/block");
    }
    private void block(UUID owner, UUID target) throws Exception { assertNull(call(owner, route(false, target), null, 204)); }
    private void unblock(UUID owner, UUID target) throws Exception { assertNull(call(owner, route(true, target), null, 204)); }
    private JsonNode blocked(UUID owner) throws Exception { return call(owner, get("/api/contacts/blocked"), null, 200); }
    private int rows() { return db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class); }
    private int pendingCount() { return db.jdbc.queryForObject("SELECT count(*) FROM contact_requests WHERE status='PENDING'", Integer.class); }
    private void friendship(boolean bothBlocked) { contact(a, b, true, bothBlocked); contact(b, a, true, bothBlocked); }

    @Test void missingJwtRejectsAllThreeEndpoints() throws Exception {
        for (var request : List.of(get("/api/contacts/blocked"), route(false, b), route(true, b)))
            code(call(null, request, null, 401), "SESSION_EXPIRED");
        assertEquals(0, rows()); assertEquals(0, events());
    }
    @Test void invalidJwtRejectsAllThreeEndpoints() throws Exception {
        for (var request : List.of(get("/api/contacts/blocked"), route(false, b), route(true, b)))
            code(call(null, request.header("Authorization", "Bearer invalid"), null, 401), "SESSION_EXPIRED");
        assertEquals(0, rows()); assertEquals(0, events());
    }
    @Test void selfTargetsAreRejected() throws Exception {
        for (boolean remove : List.of(false, true)) code(call(a, route(remove, a), null, 400), "SELF");
        assertEquals(0, rows()); assertEquals(0, events());
    }
    @Test void malformedAndUnknownTargetsAreNotFound() throws Exception {
        for (String target : List.of("invalid", UUID.randomUUID().toString())) {
            code(call(a, post("/api/contacts/" + target + "/block"), null, 404), "NOT_FOUND");
            code(call(a, delete("/api/contacts/" + target + "/block"), null, 404), "NOT_FOUND");
        }
        assertEquals(0, rows()); assertEquals(0, events());
    }
    @Test void inactiveTargetsAreNotFound() throws Exception {
        for (String state : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", state, b);
            for (boolean remove : List.of(false, true)) code(call(a, route(remove, b), null, 404), "NOT_FOUND");
        }
        assertEquals(0, rows()); assertEquals(0, events());
    }
    @Test void blockStrangerCreatesOnlyAnOwnedBlockOnlyRow() throws Exception {
        block(a, b); assertEquals(1, rows()); assertEquals(1, events());
        assertTrue(db.jdbc.queryForObject("SELECT blocked AND NOT is_friend FROM contacts WHERE owner_id=? AND contact_user_id=?", Boolean.class, a, b));
        var event = db.jdbc.queryForMap("SELECT user_id,event_type,entity_type,entity_id,payload_json::text AS payload FROM sync_events");
        assertEquals(a, event.get("user_id")); assertEquals("CONTACT_UPDATED", event.get("event_type"));
        assertEquals("CONTACT", event.get("entity_type")); assertEquals(b.toString(), event.get("entity_id"));
        JsonNode payload = json.readTree((String) event.get("payload"));
        assertEquals(5, payload.size());
        assertTrue(payload.path("blocked").asBoolean()); assertFalse(payload.path("isFriend").asBoolean());
        assertFalse(payload.path("removed").asBoolean()); assertTrue(payload.path("user").path("phone").isNull());
        assertEquals(b.toString(), payload.path("contactUserId").asText());
    }
    @Test void repeatedBlockDoesNotChangeTimestampOrDuplicateEvents() throws Exception {
        block(a, b); var before = db.jdbc.queryForList("SELECT * FROM contacts"); block(a, b);
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts")); assertEquals(1, events());
    }
    @Test void blockingFriendPreservesBothFriendFlagsAndCreationTime() throws Exception {
        friendship(false); Object created = db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a);
        block(a, b); assertEquals(2, rows()); assertEquals(1, events());
        assertEquals(2, db.jdbc.queryForObject("SELECT count(*) FROM contacts WHERE is_friend", Integer.class));
        assertEquals(created, db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a));
        assertFalse(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=?", Boolean.class, b));
    }
    /** Block hủy pending và hai event lời mời, nhưng không tạo friendship hoặc lộ phone trong payload request. */
    private void cancelPending(boolean outgoing) throws Exception {
        UUID key = pending(outgoing ? a : b, outgoing ? b : a); block(a, b);
        assertEquals("CANCELED", db.jdbc.queryForObject("SELECT status FROM contact_requests WHERE id=?", String.class, key));
        assertNotNull(db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key));
        assertEquals(0, pendingCount()); assertEquals(3, events());
        var payloads = db.jdbc.queryForList("SELECT payload_json::text FROM sync_events WHERE event_type='CONTACT_REQUEST_UPDATED'", String.class);
        assertEquals(2, payloads.size()); assertEquals(payloads.get(0), payloads.get(1));
        JsonNode request = json.readTree(payloads.get(0)); assertEquals("CANCELED", request.path("status").asText());
        assertEquals(key.toString(), request.path("id").asText()); assertEquals(3, request.path("requester").size());
        assertEquals(3, request.path("addressee").size());
        assertEquals(Set.of(a, b), new HashSet<>(db.jdbc.queryForList("SELECT user_id FROM sync_events WHERE event_type='CONTACT_REQUEST_UPDATED'", UUID.class)));
    }
    @Test void blockCancelsOutgoingInvitation() throws Exception { cancelPending(true); }
    @Test void blockCancelsIncomingInvitation() throws Exception { cancelPending(false); }
    @Test void repeatedBlockStillClosesLegacyPendingWithoutRedundantContactEvent() throws Exception {
        contact(a, b, false, true); pending(a, b); block(a, b);
        assertEquals(0, pendingCount()); assertEquals(2, events());
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM sync_events WHERE event_type='CONTACT_UPDATED'", Integer.class));
    }
    @Test void blockPreventsLookupAndSendFromBothDirections() throws Exception {
        block(a, b);
        for (UUID actor : List.of(a, b)) {
            code(call(actor, get("/api/users/lookup").param("phone", actor.equals(a) ? PHONE_B : PHONE_A), null, 404), "NOT_FOUND");
            code(call(actor, post("/api/contact-requests"), Map.of("addresseeId", (actor.equals(a) ? b : a).toString()), 403), "BLOCKED");
        }
        assertEquals(0, pendingCount()); assertEquals(1, events());
    }
    @Test void actualBlockBeforeAcceptRejectsAndKeepsInvitationCanceled() throws Exception {
        UUID key = pending(a, b); block(a, b);
        code(call(b, post("/api/contact-requests/" + key + "/accept"), null, 403), "BLOCKED");
        assertEquals(0, pendingCount()); assertEquals(1, rows()); assertEquals(3, events());
    }
    @Test void unblockingDoesNotReopenCanceledInvitation() throws Exception {
        UUID key = pending(a, b); block(a, b); unblock(a, b);
        code(call(b, post("/api/contact-requests/" + key + "/accept"), null, 409), "INVALID_STATE");
        assertEquals(0, pendingCount()); assertEquals(0, rows()); assertEquals(4, events());
    }
    @Test void blockedListOnlyContainsOwnBlockedRows() throws Exception {
        contact(a, b, false, true); contact(a, c, true, false); contact(b, c, false, true);
        JsonNode result = blocked(a); assertEquals(1, result.size()); assertEquals(b.toString(), result.get(0).path("id").asText());
        assertTrue(result.get(0).path("blocked").asBoolean()); assertTrue(result.get(0).path("phone").isNull());
        assertEquals(6, result.get(0).size()); assertEquals(0, events());
    }
    @Test void blockedFriendRetainsPhoneButBlockOnlyDoesNot() throws Exception {
        contact(a, b, true, true); contact(a, c, false, true);
        JsonNode users = blocked(a); assertEquals(2, users.size());
        for (JsonNode user : users) {
            if (user.path("id").asText().equals(b.toString())) assertEquals(PHONE_B, user.path("phone").asText());
            else assertTrue(user.path("phone").isNull());
        }
    }
    @Test void listAndMutationQueriesCannotForgeActor() throws Exception {
        block(a, b);
        assertTrue(call(c, get("/api/contacts/blocked").param("userId", a.toString()), null, 200).isEmpty());
        assertNull(call(c, route(true, b).param("userId", a.toString()), Map.of("actorId", a.toString()), 204));
        assertEquals(1, blocked(a).size()); assertEquals(1, events());
        assertNull(call(c, route(false, b).param("userId", a.toString()), Map.of("actorId", a.toString()), 204));
        assertEquals(1, blocked(c).size()); assertEquals(1, blocked(a).size()); assertEquals(2, rows()); assertEquals(2, events());
    }
    @Test void blockedListHidesInactiveTargetsButNotPrivateProfiles() throws Exception {
        block(a, b); db.jdbc.update("UPDATE users SET discoverable=FALSE WHERE id=?", b); assertEquals(1, blocked(a).size());
        db.jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", b); assertTrue(blocked(a).isEmpty());
    }
    @Test void emptyBlockedListIsArrayAndDoesNotWrite() throws Exception {
        JsonNode result = blocked(a); assertTrue(result.isArray()); assertTrue(result.isEmpty()); assertEquals(0, events());
    }
    @Test void unblockStrangerRemovesOnlyOwnRowAndEmitsRemoved() throws Exception {
        contact(a, b, false, true); contact(b, a, false, true); unblock(a, b);
        assertEquals(1, rows()); assertEquals(1, events()); assertEquals(1, blocked(b).size());
        JsonNode payload = json.readTree(db.jdbc.queryForObject("SELECT payload_json::text FROM sync_events", String.class));
        assertTrue(payload.path("removed").asBoolean()); assertFalse(payload.path("isFriend").asBoolean());
        assertFalse(payload.path("blocked").asBoolean()); assertFalse(payload.has("user"));
    }
    @Test void unblockFriendPreservesFriendshipAndCreationTime() throws Exception {
        friendship(true); Object created = db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a);
        unblock(a, b); assertEquals(2, rows()); assertEquals(1, events());
        assertEquals(created, db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a));
        assertTrue(db.jdbc.queryForObject("SELECT is_friend AND NOT blocked FROM contacts WHERE owner_id=?", Boolean.class, a));
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=?", Boolean.class, b));
    }
    @Test void mutualBlockThenUnblockDoesNotLoseFriendship() throws Exception {
        friendship(false); block(a, b); block(b, a); unblock(a, b);
        code(call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 404), "NOT_FOUND");
        unblock(b, a);
        assertEquals("FRIEND", call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 200).path("relationshipStatus").asText());
        assertEquals(2, rows()); assertEquals(4, events());
    }
    @Test void unblockAfterUnfriendDoesNotRestoreHistoricalFriendship() throws Exception {
        friendship(false); block(a, b); call(b, delete("/api/contacts/" + a), null, 204); unblock(a, b);
        assertEquals(0, rows()); assertTrue(blocked(a).isEmpty());
        assertEquals("STRANGER", call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 200).path("relationshipStatus").asText());
    }
    @Test void repeatedUnblockAndUnblockOfUnblockedFriendAreNoOps() throws Exception {
        friendship(false); unblock(a, b); assertEquals(0, events());
        block(a, b); unblock(a, b); unblock(a, b); assertEquals(2, rows()); assertEquals(2, events());
    }
    /** Gây lỗi sau khi đã đổi dữ liệu bằng CHECK thật; lần lỗi phải không để lại event hoặc block. */
    private void blockRollback(boolean rejectRequestEvent) throws Exception {
        UUID key = pending(a, b);
        String condition = rejectRequestEvent ? "event_type <> 'CONTACT_REQUEST_UPDATED' OR user_id <> '" + b + "'::uuid" : "event_type <> 'CONTACT_UPDATED'";
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_block_event CHECK (" + condition + ")");
        code(call(a, route(false, b), null, 500), "INTERNAL");
        assertEquals(0, rows()); assertEquals(0, events()); assertEquals(1, pendingCount());
        assertNull(db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key));
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_block_event"); block(a, b);
        assertEquals(0, pendingCount()); assertEquals(3, events());
    }
    @Test void secondCanceledRequestEventFailureRollsBackBlock() throws Exception { blockRollback(true); }
    @Test void contactEventFailureRollsBackCanceledRequestAndEarlierEvents() throws Exception { blockRollback(false); }
    private void unblockRollback(boolean friend) throws Exception {
        contact(a, b, friend, true); var before = db.jdbc.queryForList("SELECT * FROM contacts");
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_unblock_event CHECK (event_type <> 'CONTACT_UPDATED')");
        code(call(a, route(true, b), null, 500), "INTERNAL");
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts")); assertEquals(0, events());
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_unblock_event"); unblock(a, b);
        assertEquals(friend ? 1 : 0, rows()); assertEquals(1, events());
    }
    @Test void unblockDeletedRowIsRestoredWhenEventFails() throws Exception { unblockRollback(false); }
    @Test void unblockFriendFlagIsRestoredWhenEventFails() throws Exception { unblockRollback(true); }

    private record Outcome(int status, JsonNode body) { }
    private Outcome http(UUID actor, MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + token(actor))).andReturn().getResponse();
        return new Outcome(response.getStatus(), response.getContentAsByteArray().length == 0 ? null : json.readTree(response.getContentAsByteArray()));
    }
    private List<Outcome> race(UUID oneActor, MockHttpServletRequestBuilder one, UUID twoActor, MockHttpServletRequestBuilder two) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return http(oneActor, one); });
            Future<Outcome> second = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return http(twoActor, two); });
            start.countDown(); return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void concurrentRepeatedBlocksOnlyWriteOnce() throws Exception {
        var results = race(a, route(false, b), a, route(false, b));
        assertEquals(List.of(204, 204), results.stream().map(Outcome::status).toList()); assertEquals(1, rows()); assertEquals(1, events());
    }
    @Test void concurrentOppositeBlocksCancelPendingOnce() throws Exception {
        pending(a, b); var results = race(a, route(false, b), b, route(false, a));
        assertEquals(List.of(204, 204), results.stream().map(Outcome::status).toList()); assertEquals(2, rows()); assertEquals(0, pendingCount()); assertEquals(4, events());
    }
    @Test void concurrentMutualUnblocksPreserveBothFriends() throws Exception {
        friendship(true); var results = race(a, route(true, b), b, route(true, a));
        assertEquals(List.of(204, 204), results.stream().map(Outcome::status).toList());
        assertEquals(2, rows()); assertEquals(2, events());
        assertEquals("FRIEND", call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 200).path("relationshipStatus").asText());
    }
    @Test void actualAcceptAndBlockHaveConsistentFinalState() throws Exception {
        UUID key = pending(a, b); var results = race(b, post("/api/contact-requests/" + key + "/accept"), a, route(false, b));
        assertEquals(204, results.get(1).status()); assertTrue(Set.of(200, 403).contains(results.get(0).status()));
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=?", Boolean.class, a)); assertEquals(0, pendingCount());
        boolean accepted = results.get(0).status() == 200;
        assertEquals(accepted ? "ACCEPTED" : "CANCELED", db.jdbc.queryForObject("SELECT status FROM contact_requests WHERE id=?", String.class, key));
        assertEquals(accepted ? 2 : 1, rows()); assertEquals(accepted ? 5 : 3, events());
    }
    @Test void actualSendAndBlockCannotLeavePending() throws Exception {
        var send = post("/api/contact-requests").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("addresseeId", b.toString())));
        var results = race(a, send, b, route(false, a));
        assertTrue(Set.of(201, 403).contains(results.get(0).status())); assertEquals(204, results.get(1).status());
        assertEquals(0, pendingCount()); assertEquals(1, rows()); assertEquals(results.get(0).status() == 201 ? 5 : 1, events());
    }
    @Test void actualUnfriendAndBlockCannotLoseBlockingDecision() throws Exception {
        friendship(false); var results = race(b, delete("/api/contacts/" + a), a, route(false, b));
        assertEquals(List.of(204, 204), results.stream().map(Outcome::status).toList()); assertEquals(1, rows()); assertEquals(3, events());
        assertTrue(db.jdbc.queryForObject("SELECT blocked AND NOT is_friend FROM contacts WHERE owner_id=?", Boolean.class, a));
        assertTrue(blocked(a).get(0).path("phone").isNull());
    }
}
