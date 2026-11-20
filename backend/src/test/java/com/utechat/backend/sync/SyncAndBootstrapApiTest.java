package com.utechat.backend.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.service.SyncRetentionService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Kiểm chứng tích hợp trên EmbeddedPostgres (không cần Docker) cho Plan 05-03:
 * - Bootstrap REPEATABLE READ nhất quán dưới ghi song song
 * - 2 kết nối JDBC commit ngược thứ tự sync_events.id (id2 trước id1) không bao giờ nhảy cóc
 * - Barrier giữ sự kiện trả retryAfterMs=1000 và tự bắt kịp khi transaction chặn kết thúc
 * - Phân trang /api/sync, 410 SYNC_CURSOR_EXPIRED (retention, vượt sequence, sequence chưa gọi) và 400 after < 0
 * - Thành viên rời nhóm chỉ nhận đúng 1 sự kiện cuối và ngừng nhận tin nhắn mới của nhóm.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncAndBootstrapApiTest {
    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.flyway.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "");
        r.add("jwt.secret", () -> "utechat-phase5-sync-test-secret-0123456789abcdef");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private SyncRetentionService retentionService;

    private TestUser alice;
    private TestUser bob;
    private TestUser charlie;

    @BeforeEach
    void setUp() {
        // Đưa mốc retention về 0 trước mỗi test để các test độc lập nhau.
        jdbc.update("UPDATE public.sync_retention_state SET min_available_cursor = 0 WHERE id = 1");
        alice = createActiveUser("Alice");
        bob = createActiveUser("Bob");
        charlie = createActiveUser("Charlie");
        makeAcceptedFriends(alice.id, bob.id);
        makeAcceptedFriends(alice.id, charlie.id);
    }

    @Test
    void bootstrapReturnsConsistentSnapshotAndCursorUnderConcurrentWrite() throws Exception {
        // Tạo sẵn 1 người bị chặn (stranger block-only) và 1 lời mời PENDING để kiểm tra chính sách ẩn SĐT.
        TestUser blockedStranger = createActiveUser("BlockedStranger");
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, FALSE, TRUE)",
                alice.id, blockedStranger.id);
        TestUser pendingUser = createActiveUser("PendingUser");
        jdbc.update("INSERT INTO contact_requests (requester_id, addressee_id, status) VALUES (?, ?, 'PENDING')",
                pendingUser.id, alice.id);

        // Mở 1 transaction giữ sự kiện chưa commit trước khi bootstrap chạy, rồi commit ngay sau bootstrap.
        try (Connection c1 = dataSource.getConnection()) {
            c1.setAutoCommit(false);
            long pendingEventId;
            try (PreparedStatement ps = c1.prepareStatement(
                    "INSERT INTO sync_events (user_id, event_type, entity_type, entity_id, payload_json) "
                            + "VALUES (?, 'CONTACT_UPDATED', 'CONTACT', ?, '{\"removed\":false}'::jsonb) RETURNING id")) {
                ps.setObject(1, alice.id);
                ps.setString(2, bob.id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    pendingEventId = rs.getLong(1);
                }
            }

            // Bootstrap phải trả snapshot nhất quán (friends có phone, blocked stranger không phone, request không phone).
            MvcResult bootRes = mvc.perform(get("/api/sync/bootstrap")
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode boot = json.readTree(bootRes.getResponse().getContentAsString());
            long bootCursor = boot.get("syncCursor").asLong();
            assertEquals(alice.id.toString(), boot.path("me").path("id").asText());
            assertTrue(boot.path("friends").size() >= 2);
            assertFalse(boot.path("friends").get(0).path("phone").asText("").isEmpty());
            assertTrue(boot.path("blocked").get(0).path("phone").isMissingNode()
                    || boot.path("blocked").get(0).path("phone").isNull());
            assertTrue(boot.path("contactRequests").get(0).path("requester").path("phone").isMissingNode());
            assertTrue(bootCursor < pendingEventId);

            // Commit transaction song song rồi gọi /api/sync?after=bootCursor -> nhận đủ pendingEventId.
            c1.commit();
            MvcResult syncRes = mvc.perform(get("/api/sync")
                            .param("after", String.valueOf(bootCursor))
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode sync = json.readTree(syncRes.getResponse().getContentAsString());
            boolean found = false;
            for (JsonNode ev : sync.path("events")) {
                if (ev.get("id").asLong() == pendingEventId) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Sự kiện commit song song sau bootstrap phải xuất hiện ở /api/sync");
        }
    }

    @Test
    void outOfOrderTransactionCommitNeverSkipsLowerSyncEventId() throws Exception {
        // BÀI TEST TRỌNG TÂM D-07 / D-16: c1 lấy id1 (chưa commit), c2 lấy id2 > id1 và commit TRƯỚC.
        try (Connection c1 = dataSource.getConnection(); Connection c2 = dataSource.getConnection()) {
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);

            long id1 = insertRawSyncEvent(c1, alice.id, "ev1");
            long id2 = insertRawSyncEvent(c2, alice.id, "ev2");
            assertTrue(id2 > id1);

            // c2 commit trước trong khi c1 vẫn đang mở (chưa commit).
            c2.commit();

            long after = id1 - 1;
            MvcResult heldRes = mvc.perform(get("/api/sync")
                            .param("after", String.valueOf(after))
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode held = json.readTree(heldRes.getResponse().getContentAsString());
            // id2 phải bị barrier giữ lại để không nhảy cóc bỏ sót id1; đồng thời trả retryAfterMs = 1000.
            assertEquals(0, held.path("events").size());
            assertEquals(after, held.get("nextCursor").asLong());
            assertFalse(held.get("hasMore").asBoolean());
            assertEquals(1000L, held.get("retryAfterMs").asLong());

            // Khi c1 commit xong, gọi lại /api/sync?after=after phải nhận đủ [id1, id2] đúng thứ tự tăng dần.
            c1.commit();
            MvcResult fullRes = mvc.perform(get("/api/sync")
                            .param("after", String.valueOf(after))
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode full = json.readTree(fullRes.getResponse().getContentAsString());
            assertEquals(2, full.path("events").size());
            assertEquals(id1, full.path("events").get(0).get("id").asLong());
            assertEquals(id2, full.path("events").get(1).get("id").asLong());
            assertEquals(id2, full.get("nextCursor").asLong());
            assertTrue(full.path("retryAfterMs").isMissingNode() || full.path("retryAfterMs").isNull());
        }
    }

    @Test
    void barrierHeldEventBecomesAvailableWithoutAnotherWebSocketPush() throws Exception {
        // Một transaction KHÔNG liên quan giữ xmin; sự kiện của Alice đã commit bị barrier giữ lại rồi tự mở khi tx kia xong.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch unrelatedStarted = new CountDownLatch(1);
        CountDownLatch releaseUnrelated = new CountDownLatch(1);

        try {
            Future<Void> blocker = pool.submit(() -> {
                try (Connection unrelated = dataSource.getConnection()) {
                    unrelated.setAutoCommit(false);
                    // Ghi 1 cập nhật vô hại để PostgreSQL cấp xid thật cho transaction không liên quan này.
                    try (PreparedStatement ps = unrelated.prepareStatement(
                            "UPDATE users SET display_name = display_name WHERE id = ?")) {
                        ps.setObject(1, charlie.id);
                        ps.executeUpdate();
                    }
                    unrelatedStarted.countDown();
                    releaseUnrelated.await();
                    unrelated.commit();
                }
                return null;
            });

            unrelatedStarted.await();

            long heldEventId;
            try (Connection cAlice = dataSource.getConnection()) {
                cAlice.setAutoCommit(false);
                heldEventId = insertRawSyncEvent(cAlice, alice.id, "held-ev");
                cAlice.commit();
            }

            // Khi transaction không liên quan vẫn còn mở -> /api/sync phải báo retryAfterMs = 1000.
            long after = heldEventId - 1;
            MvcResult first = mvc.perform(get("/api/sync")
                            .param("after", String.valueOf(after))
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode firstJson = json.readTree(first.getResponse().getContentAsString());
            assertEquals(0, firstJson.path("events").size());
            assertEquals(1000L, firstJson.get("retryAfterMs").asLong());

            // Kết thúc transaction không liên quan mà KHÔNG ghi thêm bất kỳ sự kiện mới nào cho Alice.
            releaseUnrelated.countDown();
            blocker.get();

            // Lần thử lại tiếp theo tự lấy được heldEventId và retryAfterMs trở về null.
            MvcResult second = mvc.perform(get("/api/sync")
                            .param("after", String.valueOf(after))
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode secondJson = json.readTree(second.getResponse().getContentAsString());
            assertEquals(1, secondJson.path("events").size());
            assertEquals(heldEventId, secondJson.path("events").get(0).get("id").asLong());
            assertTrue(secondJson.path("retryAfterMs").isMissingNode() || secondJson.path("retryAfterMs").isNull());
        } finally {
            releaseUnrelated.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void syncPaginationAnd410CursorExpired() throws Exception {
        // after < 0 -> 400 VALIDATION.
        mvc.perform(get("/api/sync")
                        .param("after", "-1")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        // Ghi 3 sự kiện cho Alice để test phân trang limit = 2 (hasMore = true -> false).
        long id1 = insertCommittedEvent(alice.id, "p1", Instant.now().minusSeconds(3600 * 24 * 40));
        long id2 = insertCommittedEvent(alice.id, "p2", Instant.now().minusSeconds(3600 * 24 * 35));
        long id3 = insertCommittedEvent(alice.id, "p3", Instant.now());

        MvcResult page1 = mvc.perform(get("/api/sync")
                        .param("after", String.valueOf(id1 - 1))
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p1 = json.readTree(page1.getResponse().getContentAsString());
        assertEquals(2, p1.path("events").size());
        assertTrue(p1.get("hasMore").asBoolean());
        assertEquals(id2, p1.get("nextCursor").asLong());

        MvcResult page2 = mvc.perform(get("/api/sync")
                        .param("after", String.valueOf(id2))
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p2 = json.readTree(page2.getResponse().getContentAsString());
        assertEquals(1, p2.path("events").size());
        assertFalse(p2.get("hasMore").asBoolean());
        assertEquals(id3, p2.get("nextCursor").asLong());

        // Chạy dọn sự kiện quá 30 ngày -> nâng min_available_cursor >= id2 -> after=0 bị 410 SYNC_CURSOR_EXPIRED.
        long purgedUpTo = retentionService.purgeOlderThan(Instant.now().minusSeconds(3600 * 24 * 30));
        assertTrue(purgedUpTo >= id2);

        mvc.perform(get("/api/sync")
                        .param("after", "0")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SYNC_CURSOR_EXPIRED"));

        // Cursor vượt quá sequence hiện tại -> 410 SYNC_CURSOR_EXPIRED.
        mvc.perform(get("/api/sync")
                        .param("after", String.valueOf(id3 + 99999))
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SYNC_CURSOR_EXPIRED"));

        // Giả lập DB mới/reset với sequence chưa gọi (is_called = false) và client mang cursor cũ after > 0 -> 410.
        Long savedLast = jdbc.queryForObject("SELECT last_value FROM public.sync_events_id_seq", Long.class);
        try {
            jdbc.update("UPDATE public.sync_retention_state SET min_available_cursor = 0 WHERE id = 1");
            jdbc.query("SELECT setval('public.sync_events_id_seq', 1, false)", rs -> { });
            mvc.perform(get("/api/sync")
                            .param("after", "5")
                            .header("Authorization", "Bearer " + alice.token))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error.code").value("SYNC_CURSOR_EXPIRED"));
        } finally {
            jdbc.query("SELECT setval('public.sync_events_id_seq', ?, true)", rs -> { }, Math.max(1L, savedLast));
        }
    }

    @Test
    void removedGroupMemberGetsOneFinalEventAndNoSubsequentMessages() throws Exception {
        // Tạo nhóm 3 người rồi chốt cursor của Charlie.
        MvcResult createRes = mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Nhom Sync Roi",
                                "memberIds", List.of(bob.id.toString(), charlie.id.toString())))))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = json.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult bootCharlie = mvc.perform(get("/api/sync/bootstrap")
                        .header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isOk())
                .andReturn();
        long cursor = json.readTree(bootCharlie.getResponse().getContentAsString()).get("syncCursor").asLong();

        // Alice xóa Charlie khỏi nhóm và gửi thêm tin nhắn mới sau khi xóa.
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + charlie.id)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", "Tin sau khi Charlie bi xoa",
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isCreated());

        // Charlie gọi /api/sync -> chỉ nhận đúng 1 sự kiện CONVERSATION_UPDATED có leftAt != null, không có MESSAGE_CREATED nào.
        MvcResult syncCharlie = mvc.perform(get("/api/sync")
                        .param("after", String.valueOf(cursor))
                        .header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode events = json.readTree(syncCharlie.getResponse().getContentAsString()).path("events");
        assertEquals(1, events.size());
        assertEquals("CONVERSATION_UPDATED", events.get(0).get("eventType").asText());
        assertFalse(events.get(0).path("payload").path("leftAt").isNull());
    }

    private long insertRawSyncEvent(Connection c, UUID userId, String entityId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.sync_events (user_id, event_type, entity_type, entity_id, payload_json) "
                        + "VALUES (?, 'CONTACT_UPDATED', 'CONTACT', ?, '{\"removed\":false}'::jsonb) RETURNING id")) {
            ps.setObject(1, userId);
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private long insertCommittedEvent(UUID userId, String entityId, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO public.sync_events (user_id, event_type, entity_type, entity_id, payload_json, created_at) "
                        + "VALUES (?, 'CONTACT_UPDATED', 'CONTACT', ?, '{\"removed\":false}'::jsonb, ?) RETURNING id",
                Long.class,
                userId, entityId, Timestamp.from(createdAt));
    }

    private void makeAcceptedFriends(UUID a, UUID b) {
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) ON CONFLICT DO NOTHING", a, b);
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) ON CONFLICT DO NOTHING", b, a);
    }

    private TestUser createActiveUser(String name) {
        UUID userId = UUID.randomUUID();
        String phone = "+849" + randomDigits(8);
        String deviceHash = userId.toString().replace("-", "") + userId.toString().replace("-", "");
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users (id, phone_e164, password_hash, display_name, status, allow_messages_from_non_contacts) "
                        + "VALUES (?, ?, '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', ?, 'ACTIVE', TRUE)",
                userId, phone, name);
        jdbc.update(
                "INSERT INTO user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after) "
                        + "VALUES (?, ?, 'SyncPhone', TRUE, ?)",
                userId, deviceHash, Timestamp.from(now.minusSeconds(5)));
        return new TestUser(userId, jwt.issueAccessToken(userId, deviceHash));
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) s = s + "3";
        return s.substring(0, len);
    }

    private record TestUser(UUID id, String token) {
    }
}
