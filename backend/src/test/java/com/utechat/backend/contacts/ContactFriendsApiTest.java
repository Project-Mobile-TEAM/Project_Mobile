package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.utechat.backend.service.ContactPolicyService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Mục 08: danh sách theo chiều chủ sở hữu và unfriend hai chiều, kiểm bằng JWT/MVC/PostgreSQL thật. */
@Timeout(30)
class ContactFriendsApiTest extends ContactApiSupport {
    private void friendship() { contact(a, b, true, false); contact(b, a, true, false); }
    private int rows() { return db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class); }
    private JsonNode friends(UUID actor) throws Exception { return call(actor, get("/api/contacts"), null, 200); }
    private void unfriend(UUID actor, UUID target) throws Exception {
        assertNull(call(actor, delete("/api/contacts/" + target), null, 204));
    }

    private JsonNode event(UUID owner) throws Exception {
        var row = db.jdbc.queryForMap("SELECT event_type,entity_type,entity_id,payload_json::text AS payload FROM sync_events WHERE user_id=?", owner);
        UUID other = owner.equals(a) ? b : a;
        assertEquals("CONTACT_UPDATED", row.get("event_type")); assertEquals("CONTACT", row.get("entity_type"));
        assertEquals(other.toString(), row.get("entity_id"));
        JsonNode payload = json.readTree((String) row.get("payload"));
        assertEquals(other.toString(), payload.path("contactUserId").asText());
        assertFalse(payload.path("isFriend").asBoolean());
        return payload;
    }

    /** Unfriend phải giữ nguyên block/createdAt, chỉ hạ cờ bạn; block-only không được lộ SĐT trong event. */
    private void preservedBlock(UUID owner) throws Exception {
        assertFalse(db.jdbc.queryForObject("SELECT is_friend FROM contacts WHERE owner_id=?", Boolean.class, owner));
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=?", Boolean.class, owner));
        JsonNode payload = event(owner);
        assertFalse(payload.path("removed").asBoolean()); assertTrue(payload.path("blocked").asBoolean());
        assertTrue(payload.path("user").path("phone").isNull());
        assertEquals(6, payload.path("user").size());
    }

    @Test void missingJwtRejectsListAndUnfriend() throws Exception {
        code(call(null, get("/api/contacts"), null, 401), "SESSION_EXPIRED");
        code(call(null, delete("/api/contacts/" + b), null, 401), "SESSION_EXPIRED");
        assertEquals(0, rows()); assertEquals(0, events());
    }

    @Test void invalidJwtRejectsListAndUnfriend() throws Exception {
        for (var route : List.of(get("/api/contacts"), delete("/api/contacts/" + b)))
            code(call(null, route.header("Authorization", "Bearer invalid"), null, 401), "SESSION_EXPIRED");
        assertEquals(0, rows()); assertEquals(0, events());
    }

    @Test void listUsesExactContactDtoAndFriendPhone() throws Exception {
        friendship(); JsonNode user = friends(a).get(0);
        Set<String> fields = new HashSet<>(); user.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "phone", "displayName", "avatarUrl", "blocked", "createdAt"), fields);
        assertEquals(b.toString(), user.path("id").asText()); assertEquals(PHONE_B, user.path("phone").asText());
        assertEquals("Người B", user.path("displayName").asText()); assertFalse(user.path("blocked").asBoolean());
        assertEquals(0, events());
    }

    @Test void listFiltersOwnFriendAndBlockedFlags() throws Exception {
        contact(a, b, true, false); contact(a, c, false, false); contact(b, c, true, false);
        assertEquals(1, friends(a).size()); // Dòng không phải bạn và không chặn cũng không thuộc danh sách bạn.
        for (boolean friend : List.of(false, true)) {
            db.jdbc.update("UPDATE contacts SET blocked=TRUE,is_friend=? WHERE owner_id=? AND contact_user_id=?", friend, a, c);
            JsonNode result = friends(a); assertEquals(1, result.size()); assertEquals(b.toString(), result.get(0).path("id").asText());
        }
    }

    @Test void queryCannotReadAnotherAccountsFriends() throws Exception {
        friendship();
        assertTrue(call(c, get("/api/contacts").param("userId", a.toString()).param("ownerId", a.toString()), null, 200).isEmpty());
    }

    @Test void inactiveFriendsAreExcluded() throws Exception {
        friendship();
        for (String status : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, b); assertTrue(friends(a).isEmpty());
        }
    }

    @Test void privacyDoesNotHideAnExistingFriend() throws Exception {
        friendship(); db.jdbc.update("UPDATE users SET discoverable=FALSE,allow_messages_from_non_contacts=FALSE WHERE id=?", b);
        assertEquals(PHONE_B, friends(a).get(0).path("phone").asText());
    }

    @Test void reverseBlockDoesNotRewriteTheOwnersList() throws Exception {
        friendship(); db.jdbc.update("UPDATE contacts SET blocked=TRUE WHERE owner_id=?", b);
        // Danh sách dùng cờ chiều mình; tương tác/lookup vẫn phải tuân block hai chiều.
        assertEquals(1, friends(a).size()); assertTrue(friends(b).isEmpty());
        code(call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 404), "NOT_FOUND");
    }

    @Test void nullPhoneAndEqualNamesHaveStableIdOrder() throws Exception {
        contact(a, b, true, false); contact(a, c, true, false);
        db.jdbc.update("UPDATE users SET display_name='Same',phone_e164=NULL WHERE id IN (?,?)", b, c);
        JsonNode result = friends(a);
        List<String> expected = new ArrayList<>(List.of(b.toString(), c.toString())); Collections.sort(expected);
        assertEquals(expected, List.of(result.get(0).path("id").asText(), result.get(1).path("id").asText()));
        assertTrue(result.get(0).path("phone").isNull()); assertTrue(result.get(1).path("phone").isNull());
    }

    @Test void emptyAndRepeatedReadsDoNotWrite() throws Exception {
        JsonNode empty = friends(a); assertTrue(empty.isArray()); assertTrue(empty.isEmpty());
        friendship(); var before = db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id");
        friends(a); friends(a);
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id")); assertEquals(0, events());
    }

    @Test void unfriendRemovesBothRowsAndWritesOwnerEvents() throws Exception {
        friendship(); unfriend(a, b); assertEquals(0, rows()); assertEquals(2, events());
        for (UUID owner : List.of(a, b)) {
            JsonNode payload = event(owner); assertEquals(4, payload.size());
            assertTrue(payload.path("removed").asBoolean()); assertFalse(payload.path("blocked").asBoolean()); assertFalse(payload.has("user"));
        }
    }

    @Test void eitherFriendCanUnfriend() throws Exception {
        friendship(); unfriend(b, a); assertEquals(0, rows()); assertEquals(2, events());
        assertTrue(friends(a).isEmpty()); assertTrue(friends(b).isEmpty());
    }

    @Test void unfriendPreservesOwnBlockAndCreationTime() throws Exception {
        friendship(); db.jdbc.update("UPDATE contacts SET blocked=TRUE WHERE owner_id=?", a);
        Object created = db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a);
        unfriend(a, b); assertEquals(1, rows()); assertEquals(2, events()); preservedBlock(a);
        assertEquals(created, db.jdbc.queryForObject("SELECT created_at FROM contacts WHERE owner_id=?", Object.class, a));
        assertTrue(event(b).path("removed").asBoolean());
    }

    @Test void unfriendPreservesTheOtherPersonsBlock() throws Exception {
        friendship(); db.jdbc.update("UPDATE contacts SET blocked=TRUE WHERE owner_id=?", b);
        unfriend(a, b); assertEquals(1, rows()); assertEquals(2, events()); preservedBlock(b);
        assertTrue(event(a).path("removed").asBoolean());
    }

    @Test void mutualBlocksRemainAsTwoNonFriendRows() throws Exception {
        friendship(); db.jdbc.update("UPDATE contacts SET blocked=TRUE");
        unfriend(a, b); assertEquals(2, rows()); assertEquals(2, events());
        preservedBlock(a); preservedBlock(b);
    }

    @Test void existingBlockOnlyRowsAreUnchangedAndProduceNoEvents() throws Exception {
        contact(a, b, false, true); contact(b, a, false, true);
        var before = db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id");
        unfriend(a, b); unfriend(b, a);
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id")); assertEquals(0, events());
    }

    @Test void strangerAndRepeatedUnfriendAreNoOps() throws Exception {
        unfriend(a, b); assertEquals(0, events());
        friendship(); unfriend(a, b); unfriend(a, b); unfriend(b, a);
        assertEquals(0, rows()); assertEquals(2, events());
    }

    @Test void asymmetricRowOnlyNotifiesItsOwner() throws Exception {
        contact(b, a, true, false); unfriend(a, b);
        assertEquals(0, rows()); assertEquals(1, events());
        assertEquals(List.of(b), db.jdbc.queryForList("SELECT user_id FROM sync_events", UUID.class));
        assertTrue(event(b).path("removed").asBoolean());
    }

    @Test void invalidUnknownAndSelfTargetsAreRejected() throws Exception {
        for (String id : List.of("invalid", "1-1-1-1-1", UUID.randomUUID().toString()))
            code(call(a, delete("/api/contacts/" + id), null, 404), "NOT_FOUND");
        code(call(a, delete("/api/contacts/" + a), null, 400), "SELF"); assertEquals(0, rows()); assertEquals(0, events());
    }

    @Test void inactiveTargetCannotBeUnfriendedByThisEndpoint() throws Exception {
        friendship();
        for (String status : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, b);
            code(call(a, delete("/api/contacts/" + b), null, 404), "NOT_FOUND");
        }
        assertEquals(2, rows()); assertEquals(0, events());
    }

    @Test void bodyAndQueryCannotUnfriendAsAnotherPerson() throws Exception {
        friendship(); contact(b, c, true, false); contact(c, b, true, false);
        assertNull(call(a, delete("/api/contacts/" + b).param("userId", c.toString()), Map.of("actorId", c.toString()), 204));
        assertEquals(2, rows()); assertEquals(2, events());
        assertEquals(c, db.jdbc.queryForObject("SELECT contact_user_id FROM contacts WHERE owner_id=?", UUID.class, b));
        assertEquals(Set.of(a, b), new HashSet<>(db.jdbc.queryForList("SELECT user_id FROM sync_events", UUID.class)));
    }

    @Test void unfriendDoesNotCancelAnExistingInvitation() throws Exception {
        UUID key = pending(a, b); unfriend(a, b);
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM contact_requests WHERE id=?", String.class, key));
        assertEquals(0, rows()); assertEquals(0, events());
    }

    @Test void secondBlockedRowFailureRollsBackFlagsAndEvents() throws Exception {
        friendship(); db.jdbc.update("UPDATE contacts SET blocked=TRUE");
        var before = db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id"); UUID hi = a.compareTo(b) > 0 ? a : b;
        db.jdbc.execute("ALTER TABLE contacts ADD CONSTRAINT test_unfriend_flag CHECK (owner_id <> '" + hi + "'::uuid OR is_friend)");
        code(call(a, delete("/api/contacts/" + b), null, 500), "INTERNAL");
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id")); assertEquals(0, events());
        db.jdbc.execute("ALTER TABLE contacts DROP CONSTRAINT test_unfriend_flag");
        unfriend(a, b); preservedBlock(a); preservedBlock(b); assertEquals(2, events());
    }

    @Test void secondEventFailureRestoresBothDeletedRowsAndRetryWorks() throws Exception {
        friendship(); var before = db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id"); UUID hi = a.compareTo(b) > 0 ? a : b;
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_unfriend_event CHECK (user_id <> '" + hi + "'::uuid)");
        code(call(a, delete("/api/contacts/" + b), null, 500), "INTERNAL");
        assertEquals(before, db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id")); assertEquals(0, events());
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_unfriend_event");
        unfriend(a, b); assertEquals(0, rows()); assertEquals(2, events());
    }

    private int threaded(UUID actor, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token(actor))).andReturn().getResponse().getStatus();
    }

    /** Hai HTTP DELETE cùng hoặc ngược chiều chỉ phát một bộ event cho thay đổi thật. */
    private void concurrentUnfriend(boolean reverse) throws Exception {
        friendship(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(a, delete("/api/contacts/" + b)); });
            Future<Integer> second = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(reverse ? b : a, delete("/api/contacts/" + (reverse ? a : b))); });
            start.countDown(); assertEquals(204, first.get(10, TimeUnit.SECONDS)); assertEquals(204, second.get(10, TimeUnit.SECONDS));
            assertEquals(0, rows()); assertEquals(2, events());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentSameDirectionUnfriendsOnlyOnce() throws Exception { concurrentUnfriend(false); }
    @Test void concurrentOppositeDirectionUnfriendsOnlyOnce() throws Exception { concurrentUnfriend(true); }

    @Test void concurrentUnfriendAndHistoricalAcceptCannotRestoreFriendship() throws Exception {
        UUID key = pending(a, b); call(b, post("/api/contact-requests/" + key + "/accept"), null, 200);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> remove = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(a, delete("/api/contacts/" + b)); });
            Future<Integer> repeat = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(b, post("/api/contact-requests/" + key + "/accept")); });
            start.countDown(); assertEquals(204, remove.get(10, TimeUnit.SECONDS)); assertEquals(200, repeat.get(10, TimeUnit.SECONDS));
            assertEquals(0, rows()); assertEquals(6, events());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentBlockTransactionAndUnfriendPreserveBlockOnly() throws Exception {
        friendship(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> remove = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return threaded(b, delete("/api/contacts/" + a)); });
            Future<?> block = pool.submit(() -> {
                assertTrue(assertDoesNotThrow(() -> start.await(5, TimeUnit.SECONDS)));
                // Fixture SQL của 08, không gọi hoặc nghiệm thu API block mục 09.
                new TransactionTemplate(new DataSourceTransactionManager(db.source)).executeWithoutResult(status -> {
                    new ContactPolicyService(db.jdbc).lockPair(a, b);
                    db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,FALSE,TRUE) "
                            + "ON CONFLICT(owner_id,contact_user_id) DO UPDATE SET blocked=TRUE", a, b);
                });
            });
            start.countDown(); assertEquals(204, remove.get(10, TimeUnit.SECONDS)); block.get(10, TimeUnit.SECONDS);
            assertEquals(1, rows()); assertEquals(2, events());
            assertTrue(db.jdbc.queryForObject("SELECT blocked AND NOT is_friend FROM contacts WHERE owner_id=?", Boolean.class, a));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void sendAcceptListUnfriendAndHistoricalAcceptFormAnIntegratedFlow() throws Exception {
        JsonNode sent = call(a, post("/api/contact-requests"), Map.of("addresseeId", b.toString()), 201);
        String key = sent.path("id").asText(); call(b, post("/api/contact-requests/" + key + "/accept"), null, 200);
        assertEquals(1, friends(a).size()); assertEquals(1, friends(b).size());
        unfriend(a, b); assertTrue(friends(a).isEmpty()); assertTrue(friends(b).isEmpty());
        assertEquals("STRANGER", call(a, get("/api/users/lookup").param("phone", PHONE_B), null, 200).path("relationshipStatus").asText());
        call(b, post("/api/contact-requests/" + key + "/accept"), null, 200);
        assertEquals(0, rows()); assertEquals(8, events());
        // Chỉ lời mời mới được accept mới có thể thiết lập lại bạn bè sau unfriend.
        JsonNode newer = call(b, post("/api/contact-requests"), Map.of("addresseeId", a.toString()), 201);
        call(a, post("/api/contact-requests/" + newer.path("id").asText() + "/accept"), null, 200);
        assertEquals(1, friends(a).size()); assertEquals(1, friends(b).size()); assertEquals(14, events());
    }
}
