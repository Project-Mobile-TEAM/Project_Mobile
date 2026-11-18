package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.utechat.backend.service.ContactPolicyService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mục 06: accept thật qua Bearer/MVC và PostgreSQL; dữ liệu nền khác được seed bằng SQL. */
@Timeout(30)
class ContactAcceptApiTest extends ContactApiSupport {
    private JsonNode accept(UUID actor, UUID key, int expected) throws Exception {
        return call(actor, post("/api/contact-requests/" + key + "/accept"), null, expected);
    }

    private int friends() { return db.jdbc.queryForObject("SELECT count(*) FROM contacts WHERE is_friend", Integer.class); }
    private String state(UUID key) { return db.jdbc.queryForObject("SELECT status FROM contact_requests WHERE id=?", String.class, key); }

    /** Khi accept bị từ chối/lỗi, pending chưa phản hồi và không có tác dụng phụ từ accept. */
    private void unchanged(UUID key) {
        assertEquals("PENDING", state(key));
        assertNull(db.jdbc.queryForObject("SELECT responded_at FROM contact_requests WHERE id=?", Object.class, key));
        assertEquals(0, friends()); assertEquals(0, events());
    }

    @Test void missingJwtIsUnauthorized() throws Exception {
        UUID key = pending(a, b);
        code(accept(null, key, 401), "SESSION_EXPIRED"); unchanged(key);
    }

    @Test void invalidJwtIsUnauthorized() throws Exception {
        UUID key = pending(a, b);
        code(call(null, post("/api/contact-requests/" + key + "/accept").header("Authorization", "Bearer invalid"), null, 401), "SESSION_EXPIRED");
        unchanged(key);
    }

    @Test void malformedAndUnknownRequestIdsAreNotFound() throws Exception {
        for (String key : List.of("not-a-uuid", "1-1-1-1-1", UUID.randomUUID().toString()))
            code(call(b, post("/api/contact-requests/" + key + "/accept"), null, 404), "NOT_FOUND");
        assertEquals(0, friends()); assertEquals(0, events());
    }

    @Test void requesterCannotAcceptOwnInvitation() throws Exception {
        UUID key = pending(a, b); code(accept(a, key, 403), "FORBIDDEN"); unchanged(key);
    }

    @Test void thirdPartyCannotAcceptInvitation() throws Exception {
        UUID key = pending(a, b); code(accept(c, key, 403), "FORBIDDEN"); unchanged(key);
    }

    @Test void bodyAndQueryCannotForgeRecipient() throws Exception {
        UUID key = pending(a, b);
        code(call(c, post("/api/contact-requests/" + key + "/accept").param("userId", b.toString()),
                Map.of("addresseeId", b.toString(), "actorId", b.toString()), 403), "FORBIDDEN");
        unchanged(key);
    }

    @Test void inactiveRequesterIsNotFound() throws Exception {
        UUID key = pending(a, b);
        for (String status : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, a);
            code(accept(b, key, 404), "NOT_FOUND"); unchanged(key);
        }
    }

    @Test void inactiveRecipientCannotUseItsOldJwt() throws Exception {
        UUID key = pending(a, b);
        for (String status : List.of("LOCKED", "DELETED")) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, b);
            code(accept(b, key, 401), "SESSION_EXPIRED"); unchanged(key);
        }
    }

    @Test void requesterBlockPreventsAcceptance() throws Exception {
        UUID key = pending(a, b); contact(a, b, false, true);
        code(accept(b, key, 403), "BLOCKED"); unchanged(key);
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts", Boolean.class));
    }

    @Test void recipientBlockPreventsAcceptance() throws Exception {
        UUID key = pending(a, b); contact(b, a, false, true);
        code(accept(b, key, 403), "BLOCKED"); unchanged(key);
        assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts", Boolean.class));
    }

    @Test void rejectedAndCanceledInvitationsCannotBeAccepted() throws Exception {
        for (String status : List.of("REJECTED", "CANCELED")) {
            UUID key = pending(a, b);
            db.jdbc.update("UPDATE contact_requests SET status=?,responded_at=now() WHERE id=?", status, key);
            code(accept(b, key, 409), "INVALID_STATE");
            assertEquals(status, state(key));
        }
        assertEquals(0, friends()); assertEquals(0, events());
    }

    @Test void acceptanceReturnsMinimalRequestAndTwoFriendRows() throws Exception {
        UUID key = pending(a, b);
        JsonNode result = accept(b, key, 200);
        assertEquals(key.toString(), result.path("id").asText());
        assertEquals("ACCEPTED", result.path("status").asText());
        assertDoesNotThrow(() -> Instant.parse(result.path("respondedAt").asText()));
        assertEquals(a.toString(), result.path("requester").path("id").asText());
        assertEquals(b.toString(), result.path("addressee").path("id").asText());
        assertEquals(6, result.size());
        assertEquals(3, result.path("requester").size()); assertEquals(3, result.path("addressee").size());
        assertFalse(result.path("requester").has("phone")); assertFalse(result.path("addressee").has("phone"));
        assertEquals(2, friends());
        assertEquals(Set.of(a, b), new HashSet<>(db.jdbc.queryForList("SELECT owner_id FROM contacts WHERE is_friend AND NOT blocked", UUID.class)));
        assertEquals(b, db.jdbc.queryForObject("SELECT contact_user_id FROM contacts WHERE owner_id=?", UUID.class, a));
        assertEquals(a, db.jdbc.queryForObject("SELECT contact_user_id FROM contacts WHERE owner_id=?", UUID.class, b));
    }

    @Test void acceptanceWritesExactJsonbEventsForBothOwners() throws Exception {
        UUID key = pending(a, b);
        JsonNode result = accept(b, key, 200);
        assertEquals(4, events());
        for (UUID owner : List.of(a, b)) {
            UUID other = owner.equals(a) ? b : a;
            var rows = db.jdbc.queryForList("SELECT event_type,entity_type,entity_id,payload_json::text AS payload FROM sync_events WHERE user_id=?", owner);
            assertEquals(2, rows.size());
            for (var row : rows) {
                JsonNode payload = json.readTree((String) row.get("payload"));
                if ("CONTACT_REQUEST_UPDATED".equals(row.get("event_type"))) {
                    assertEquals("CONTACT_REQUEST", row.get("entity_type"));
                    assertEquals(key.toString(), row.get("entity_id")); assertEquals(result, payload);
                } else {
                    assertEquals("CONTACT_UPDATED", row.get("event_type")); assertEquals("CONTACT", row.get("entity_type"));
                    assertEquals(other.toString(), row.get("entity_id"));
                    assertEquals(other.toString(), payload.path("contactUserId").asText());
                    assertTrue(payload.path("isFriend").asBoolean());
                    assertFalse(payload.path("blocked").asBoolean()); assertFalse(payload.path("removed").asBoolean());
                    JsonNode user = payload.path("user");
                    assertEquals(other.toString(), user.path("id").asText());
                    assertEquals(owner.equals(a) ? PHONE_B : PHONE_A, user.path("phone").asText());
                    assertEquals(6, user.size()); assertFalse(user.path("blocked").asBoolean());
                    assertDoesNotThrow(() -> Instant.parse(user.path("createdAt").asText()));
                }
            }
        }
    }

    @Test void repeatedAcceptanceDoesNotChangeTimestampRowsOrEvents() throws Exception {
        UUID key = pending(a, b);
        JsonNode first = accept(b, key, 200);
        var contacts = db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id");
        assertEquals(first, accept(b, key, 200));
        assertEquals(contacts, db.jdbc.queryForList("SELECT * FROM contacts ORDER BY owner_id"));
        assertEquals(2, friends()); assertEquals(4, events());
        code(accept(a, key, 403), "FORBIDDEN");
    }

    @Test void historicalAcceptedAfterUnfriendDoesNotRecreateFriends() throws Exception {
        UUID key = pending(a, b);
        JsonNode first = accept(b, key, 200);
        // Seed trạng thái sau unfriend bằng SQL, không gọi API mục 08 chưa nghiệm thu.
        db.jdbc.update("DELETE FROM contacts WHERE owner_id IN (?,?)", a, b);
        assertEquals(first, accept(b, key, 200));
        assertEquals(0, friends()); assertEquals(4, events());
    }

    @Test void historicalAcceptanceDoesNotConsumeANewerPendingInvitation() throws Exception {
        UUID old = pending(a, b); accept(b, old, 200);
        db.jdbc.update("DELETE FROM contacts");
        UUID newer = pending(b, a);
        accept(b, old, 200);
        assertEquals("PENDING", state(newer)); assertEquals(0, friends()); assertEquals(4, events());
        accept(a, newer, 200);
        assertEquals(2, friends()); assertEquals(8, events());
    }

    @Test void existingNonFriendRowsKeepTheirCreationTimestamp() throws Exception {
        UUID key = pending(a, b); contact(a, b, false, false); contact(b, a, false, false);
        db.jdbc.update("UPDATE contacts SET created_at='2020-01-01T00:00:00Z'");
        var before = db.jdbc.queryForList("SELECT created_at FROM contacts ORDER BY owner_id");
        accept(b, key, 200);
        assertEquals(before, db.jdbc.queryForList("SELECT created_at FROM contacts ORDER BY owner_id"));
        assertEquals(2, friends()); assertEquals(4, events());
    }

    @Test void unchangedFriendDirectionDoesNotProduceRedundantContactEvent() throws Exception {
        UUID key = pending(a, b); contact(a, b, true, false);
        accept(b, key, 200);
        assertEquals(2, friends()); assertEquals(3, events());
        assertEquals(List.of(b), db.jdbc.queryForList("SELECT user_id FROM sync_events WHERE event_type='CONTACT_UPDATED'", UUID.class));
    }

    @Test void knownPrivateProfilesCanAcceptExistingInvitation() throws Exception {
        UUID key = pending(a, b);
        db.jdbc.update("UPDATE users SET discoverable=FALSE,allow_messages_from_non_contacts=FALSE WHERE id IN (?,?)", a, b);
        assertEquals("ACCEPTED", accept(b, key, 200).path("status").asText());
        assertEquals(2, friends());
    }

    @Test void secondFriendRowFailureRollsBackEverythingAndRetryWorks() throws Exception {
        UUID key = pending(a, b);
        UUID hi = a.compareTo(b) > 0 ? a : b;
        db.jdbc.execute("ALTER TABLE contacts ADD CONSTRAINT test_accept_second_row CHECK (owner_id <> '" + hi + "'::uuid)");
        code(accept(b, key, 500), "INTERNAL"); unchanged(key);
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
        db.jdbc.execute("ALTER TABLE contacts DROP CONSTRAINT test_accept_second_row");
        accept(b, key, 200); assertEquals(2, friends()); assertEquals(4, events());
    }

    @Test void secondRequestEventFailureRollsBackFriendsAndContactEvents() throws Exception {
        UUID key = pending(a, b);
        db.jdbc.execute("ALTER TABLE sync_events ADD CONSTRAINT test_accept_last_event CHECK "
                + "(event_type <> 'CONTACT_REQUEST_UPDATED' OR user_id <> '" + b + "'::uuid)");
        code(accept(b, key, 500), "INTERNAL"); unchanged(key);
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
        db.jdbc.execute("ALTER TABLE sync_events DROP CONSTRAINT test_accept_last_event");
        accept(b, key, 200); assertEquals(2, friends()); assertEquals(4, events());
    }

    private record Outcome(int status, JsonNode body) { }

    /** Dùng Bearer qua MockMvc ngay cả khi gọi từ executor, không truyền authentication giả. */
    private Outcome requestInThread(UUID key) throws Exception {
        var response = mvc.perform(post("/api/contact-requests/" + key + "/accept")
                .header("Authorization", "Bearer " + token(b)).contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        return new Outcome(response.getStatus(), json.readTree(response.getContentAsByteArray()));
    }

    @Test void twoConcurrentAcceptsHaveOneStateTransitionAndFourEvents() throws Exception {
        UUID key = pending(a, b);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) jobs.add(pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return requestInThread(key); }));
            start.countDown();
            Outcome first = jobs.get(0).get(10, TimeUnit.SECONDS), second = jobs.get(1).get(10, TimeUnit.SECONDS);
            assertEquals(200, first.status()); assertEquals(200, second.status()); assertEquals(first.body(), second.body());
            assertEquals("ACCEPTED", state(key)); assertEquals(2, friends()); assertEquals(4, events());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(new DataSourceTransactionManager(db.source)); }

    /** Fixture block tuân cùng khóa cặp; không đưa API block của mục 09 vào commit hoặc giả đã nghiệm thu nó. */
    private void blockInCurrentTransaction() {
        new ContactPolicyService(db.jdbc).lockPair(a, b);
        db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,FALSE,TRUE) "
                + "ON CONFLICT(owner_id,contact_user_id) DO UPDATE SET blocked=TRUE", a, b);
        db.jdbc.update("UPDATE contact_requests SET status='CANCELED',responded_at=now() WHERE status='PENDING' "
                + "AND ((requester_id=? AND addressee_id=?) OR (requester_id=? AND addressee_id=?))", a, b, b, a);
    }

    @Test void committedBlockIsRecheckedAfterAcceptanceWaitsForPairLock() throws Exception {
        UUID key = pending(a, b);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<Outcome>> jobs = new ArrayList<>();
        try {
            transaction().executeWithoutResult(status -> {
                blockInCurrentTransaction();
                jobs.add(pool.submit(() -> requestInThread(key)));
                // Xác nhận thread accept thực sự chờ advisory lock tại PostgreSQL, không dựa vào sleep.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    db.jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity "
                            + "WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory')", Boolean.class));
                    if (waiting) break;
                    assertDoesNotThrow(() -> Thread.sleep(10));
                }
                assertTrue(waiting, "Accept phải chờ khóa cặp của block tại PostgreSQL");
                assertFalse(jobs.get(0).isDone());
            });
            Outcome result = jobs.get(0).get(10, TimeUnit.SECONDS);
            assertEquals(403, result.status()); code(result.body(), "BLOCKED");
            assertEquals("CANCELED", state(key)); assertEquals(0, friends()); assertEquals(0, events());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentAcceptanceAndBlockCannotBypassBlockOrLeavePending() throws Exception {
        UUID key = pending(a, b);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> acceptance = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return requestInThread(key); });
            Future<?> blocking = pool.submit(() -> {
                assertTrue(assertDoesNotThrow(() -> start.await(5, TimeUnit.SECONDS)));
                transaction().executeWithoutResult(status -> blockInCurrentTransaction());
            });
            start.countDown(); Outcome result = acceptance.get(10, TimeUnit.SECONDS); blocking.get(10, TimeUnit.SECONDS);
            assertTrue(Set.of(200, 403).contains(result.status()));
            assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=? AND contact_user_id=?", Boolean.class, a, b));
            assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM contact_requests WHERE status='PENDING'", Integer.class));
            if (result.status() == 200) { assertEquals("ACCEPTED", state(key)); assertEquals(2, friends()); assertEquals(4, events()); }
            else { code(result.body(), "BLOCKED"); assertEquals("CANCELED", state(key)); assertEquals(0, friends()); assertEquals(0, events()); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void sendListAcceptAndLookupFormAnIntegratedBackendFlow() throws Exception {
        JsonNode sent = call(a, post("/api/contact-requests"), Map.of("addresseeId", b.toString()), 201);
        UUID key = UUID.fromString(sent.path("id").asText());
        assertEquals(sent, call(b, get("/api/contact-requests").param("type", "incoming"), null, 200).get(0));
        assertEquals(sent, call(a, get("/api/contact-requests").param("type", "outgoing"), null, 200).get(0));
        JsonNode accepted = accept(b, key, 200);
        for (UUID actor : List.of(a, b)) {
            assertTrue(call(actor, get("/api/contact-requests"), null, 200).isEmpty());
            assertEquals(accepted, call(actor, get("/api/contact-requests").param("status", "ACCEPTED"), null, 200).get(0));
            JsonNode lookup = call(actor, get("/api/users/lookup").param("phone", actor.equals(a) ? PHONE_B : PHONE_A), null, 200);
            assertEquals("FRIEND", lookup.path("relationshipStatus").asText()); assertTrue(lookup.path("isContact").asBoolean());
        }
        assertEquals(2, friends()); assertEquals(6, events());
    }
}
