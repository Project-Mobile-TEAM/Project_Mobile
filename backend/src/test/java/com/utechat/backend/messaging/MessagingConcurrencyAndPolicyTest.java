package com.utechat.backend.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Bộ kiểm thử tích hợp trên EmbeddedPostgres (không cần Docker) cho Plan 05-01:
 * đua song song direct_key, thứ tự ưu tiên 403 BLOCKED trước CONTACT_REQUIRED,
 * chống trùng clientId, rate limit 30 tin/10s, chặn type=SYSTEM và phân trang lịch sử kèm metadata.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessagingConcurrencyAndPolicyTest {
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
        r.add("jwt.secret", () -> "utechat-phase5-test-jwt-secret-0123456789abcdef");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;

    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void resetUsers() {
        alice = createActiveUser("+8491" + randomDigits(7), "Alice", true);
        bob = createActiveUser("+8492" + randomDigits(7), "Bob", true);
    }

    @Test
    void concurrentDirectConversationCreatesSingleRowWithDirectKey() throws Exception {
        // Hai luồng đồng thời gọi POST /api/conversations/direct theo hai chiều ngược nhau.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult res = mvc.perform(post("/api/conversations/direct")
                                .header("Authorization", "Bearer " + alice.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("userId", bob.id.toString()))))
                        .andExpect(status().isOk())
                        .andReturn();
                return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
            });
            Future<String> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult res = mvc.perform(post("/api/conversations/direct")
                                .header("Authorization", "Bearer " + bob.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("userId", alice.id.toString()))))
                        .andExpect(status().isOk())
                        .andReturn();
                return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
            });

            ready.await();
            start.countDown();

            String convId1 = f1.get();
            String convId2 = f2.get();
            assertEquals(convId1, convId2);

            String lo = alice.id.toString().compareTo(bob.id.toString()) <= 0
                    ? alice.id + ":" + bob.id
                    : bob.id + ":" + alice.id;
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM conversations WHERE direct_key = ?", Integer.class, lo);
            assertEquals(1, count);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blockedTakesPrecedenceOverContactRequiredBothAtOpenAndSend() throws Exception {
        // Bob tắt nhận tin người lạ -> Alice (chưa kết bạn) mở chat bị 403 CONTACT_REQUIRED.
        jdbc.update("UPDATE users SET allow_messages_from_non_contacts = FALSE WHERE id = ?", bob.id);
        mvc.perform(post("/api/conversations/direct")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", bob.id.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CONTACT_REQUIRED"));

        // Bob bật lại -> mở được hội thoại 1-1 -> sau đó Bob tắt lại -> gửi tin bị 403 CONTACT_REQUIRED.
        jdbc.update("UPDATE users SET allow_messages_from_non_contacts = TRUE WHERE id = ?", bob.id);
        String convId = openDirect(alice, bob);

        jdbc.update("UPDATE users SET allow_messages_from_non_contacts = FALSE WHERE id = ?", bob.id);
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", "Xin chao nguoi la"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CONTACT_REQUIRED"));

        // Bob chặn Alice (vẫn giữ allow_messages_from_non_contacts = FALSE) -> cả mở chat lẫn gửi tin phải trả 403 BLOCKED.
        jdbc.update("INSERT INTO contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, FALSE, TRUE)",
                bob.id, alice.id);

        mvc.perform(post("/api/conversations/direct")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", bob.id.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BLOCKED"));

        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", "Tin bi chan"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BLOCKED"));
    }

    @Test
    void clientIdIdempotencyAvoidsDuplicateMessagesAndSyncEvents() throws Exception {
        String convId = openDirect(alice, bob);
        String clientId = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of(
                "clientId", clientId,
                "type", "TEXT",
                "text", "Tin chong trung"));

        // Gửi lần đầu -> 201 Created.
        MvcResult first = mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        long msgId = json.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        // Gửi lặp tuần tự cùng clientId -> 200 OK trả đúng messageId cũ.
        MvcResult second = mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(msgId, json.readTree(second.getResponse().getContentAsString()).get("id").asLong());

        // Kiểm tra chỉ có đúng 1 dòng message và đúng 1 sự kiện MESSAGE_CREATED cho mỗi thành viên.
        Integer msgCount = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE sender_id = ? AND client_id = ?",
                Integer.class, alice.id, UUID.fromString(clientId));
        assertEquals(1, msgCount);

        Integer aliceEvents = jdbc.queryForObject(
                "SELECT count(*) FROM sync_events WHERE user_id = ? AND event_type = 'MESSAGE_CREATED' AND entity_id = ?",
                Integer.class, alice.id, String.valueOf(msgId));
        Integer bobEvents = jdbc.queryForObject(
                "SELECT count(*) FROM sync_events WHERE user_id = ? AND event_type = 'MESSAGE_CREATED' AND entity_id = ?",
                Integer.class, bob.id, String.valueOf(msgId));
        assertEquals(1, aliceEvents);
        assertEquals(1, bobEvents);

        // Dùng lại cùng clientId ở hội thoại khác -> 409 CONFLICT.
        TestUser charlie = createActiveUser("+8493" + randomDigits(7), "Charlie", true);
        String otherConvId = openDirect(alice, charlie);
        mvc.perform(post("/api/conversations/" + otherConvId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsClientSystemTypeAndEnforcesRateLimit30Per10s() throws Exception {
        String convId = openDirect(alice, bob);

        // Client không bao giờ được phép tự gửi tin loại SYSTEM.
        mvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "SYSTEM",
                                "text", "Gia mao he thong"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        // Dùng một tài khoản mới riêng cho test rate limit để không bị ảnh hưởng bởi các tin ở test khác.
        TestUser spammer = createActiveUser("+8494" + randomDigits(7), "Spammer", true);
        String spamConv = openDirect(spammer, bob);
        for (int i = 1; i <= 30; i++) {
            mvc.perform(post("/api/conversations/" + spamConv + "/messages")
                            .header("Authorization", "Bearer " + spammer.token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "clientId", UUID.randomUUID().toString(),
                                    "type", "TEXT",
                                    "text", "Tin thu " + i))))
                    .andExpect(status().isCreated());
        }

        // Tin thứ 31 trong cùng cửa sổ 10 giây phải bị chặn 429 RATE_LIMITED kèm retryAfterSeconds >= 1.
        MvcResult limited = mvc.perform(post("/api/conversations/" + spamConv + "/messages")
                        .header("Authorization", "Bearer " + spammer.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", "Tin thu 31"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andReturn();
        JsonNode root = json.readTree(limited.getResponse().getContentAsString());
        assertTrue(root.path("error").path("details").path("retryAfterSeconds").asLong() >= 1L);
    }

    @Test
    void historyPaginationAndMetadataPreservation() throws Exception {
        String convId = openDirect(alice, bob);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            MvcResult res = mvc.perform(post("/api/conversations/" + convId + "/messages")
                            .header("Authorization", "Bearer " + alice.token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "clientId", UUID.randomUUID().toString(),
                                    "type", "TEXT",
                                    "text", "Msg " + i))))
                    .andExpect(status().isCreated())
                    .andReturn();
            ids.add(json.readTree(res.getResponse().getContentAsString()).get("id").asLong());
        }

        // Chèn thêm 1 bản ghi IMAGE có attachment thật trong CSDL để kiểm chứng bảo toàn metadata Phase 6.
        UUID attId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO attachments (id, uploader_id, kind, mime, size_bytes, storage_path, original_name, width, height) "
                        + "VALUES (?, ?, 'IMAGE', 'image/jpeg', 204800, '/var/data/a.jpg', 'photo.jpg', 1200, 800)",
                attId, alice.id);
        Long imgMsgId = jdbc.queryForObject(
                "INSERT INTO messages (conversation_id, sender_id, client_id, type, attachment_id) "
                        + "VALUES (?, ?, ?, 'IMAGE', ?) RETURNING id",
                Long.class, UUID.fromString(convId), alice.id, UUID.randomUUID(), attId);
        ids.add(imgMsgId);

        // Trang 1 (limit=2): phải trả về imgMsgId và ids[2] theo thứ tự mới nhất trước, giữ nguyên metadata IMAGE.
        MvcResult page1 = mvc.perform(get("/api/conversations/" + convId + "/messages")
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p1 = json.readTree(page1.getResponse().getContentAsString());
        assertEquals(2, p1.size());
        assertEquals(imgMsgId, p1.get(0).get("id").asLong());
        assertEquals("IMAGE", p1.get(0).get("type").asText());
        assertEquals("/api/media/" + attId, p1.get(0).get("mediaUrl").asText());
        assertEquals("image/jpeg", p1.get(0).get("mime").asText());
        assertEquals(204800L, p1.get(0).get("sizeBytes").asLong());
        assertEquals(1200, p1.get(0).get("width").asInt());
        assertEquals(800, p1.get(0).get("height").asInt());
        assertEquals(ids.get(2), p1.get(1).get("id").asLong());

        // Trang 2 (?before=ids[2]&limit=2): phải trả về ids[1] và ids[0], không trùng với trang 1.
        MvcResult page2 = mvc.perform(get("/api/conversations/" + convId + "/messages")
                        .param("before", String.valueOf(ids.get(2)))
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode p2 = json.readTree(page2.getResponse().getContentAsString());
        assertEquals(2, p2.size());
        assertEquals(ids.get(1), p2.get(0).get("id").asLong());
        assertEquals(ids.get(0), p2.get(1).get("id").asLong());
    }

    private String openDirect(TestUser caller, TestUser peer) throws Exception {
        MvcResult res = mvc.perform(post("/api/conversations/direct")
                        .header("Authorization", "Bearer " + caller.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", peer.id.toString()))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private TestUser createActiveUser(String phone, String name, boolean allowStrangers) {
        UUID userId = UUID.randomUUID();
        String deviceHash = (userId.toString().replace("-", "") + userId.toString().replace("-", ""));
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users (id, phone_e164, password_hash, display_name, status, allow_messages_from_non_contacts) "
                        + "VALUES (?, ?, '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', ?, 'ACTIVE', ?)",
                userId, phone, name, allowStrangers);
        jdbc.update(
                "INSERT INTO user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after) "
                        + "VALUES (?, ?, 'TestPhone', TRUE, ?)",
                userId, deviceHash, java.sql.Timestamp.from(now.minusSeconds(5)));
        String token = jwt.issueAccessToken(userId, deviceHash);
        return new TestUser(userId, token);
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) s = s + "7";
        return s.substring(0, len);
    }

    private record TestUser(UUID id, String token) {
    }
}
