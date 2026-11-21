package com.utechat.backend.ws;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.service.SyncEventWriter;
import com.utechat.backend.websocket.WebSocketSessionRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kiểm chứng tích hợp WebSocket thật trên Tomcat + EmbeddedPostgres (không cần Docker) cho Plan 05-03:
 * - Chặn thiếu Bearer (401) và chặn JWT trong query URL (400 kể cả khi có hay không có Bearer)
 * - Handshake Bearer thành công, JSON ping/pong 25s giữ kết nối sống > 90s giả lập, đóng idle > 75s và 4401 khi bị thu hồi
 * - Đẩy tin sau commit (afterCommit), không đẩy khi rollback, throttle typing.start 1/3s
 * - Thu hồi thiết bị gửi auth.revoked + đóng 4401, đăng xuất đóng 4401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketAuthAndRealtimeTest {
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
        r.add("jwt.secret", () -> "utechat-phase5-ws-test-secret-0123456789abcdef");
    }

    /** Đồng hồ giả lập có thể tua nhanh thời gian giúp test heartbeat > 90s không cần sleep thật. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

        void resetToNow() {
            current.set(Instant.now());
        }

        void advance(Duration d) {
            current.updateAndGet(t -> t.plus(d));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }

    @TestConfiguration
    static class ClockOverrideConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private MutableClock clock;
    @Autowired private WebSocketSessionRegistry wsRegistry;
    @Autowired private SyncEventWriter syncEventWriter;
    @Autowired private PlatformTransactionManager txManager;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void setUp() {
        clock.resetToNow();
        alice = createActiveUser("Alice");
        bob = createActiveUser("Bob");
    }

    @Test
    void rejectsMissingTokenAndJwtInQueryParams() throws Exception {
        // Thiếu header Authorization -> 401.
        assertHandshakeFails(wsUri("/ws"), null, 401);

        // Chứa JWT trong query param -> 400 (kể cả khi KHÔNG có hay CÓ header Bearer hợp lệ).
        for (String param : new String[]{"token", "jwt", "access_token", "bearer", "authorization"}) {
            assertHandshakeFails(wsUri("/ws?" + param + "=" + alice.token), null, 400);
            assertHandshakeFails(wsUri("/ws?" + param + "=" + alice.token), alice.token, 400);
        }
    }

    @Test
    void acceptsBearerHandshakeAndHandlesAppPingPong() throws Exception {
        TestWsClient client = connect(alice.token);
        try {
            client.sendText("{\"type\":\"ping\"}");
            JsonNode pong = client.awaitMessage(5);
            assertNotNull(pong);
            assertEquals(1, pong.get("v").asInt());
            assertEquals("pong", pong.get("type").asText());
        } finally {
            client.close();
        }
    }

    @Test
    void appHeartbeatKeepsIdleSocketAliveAndStopsRevokedDevice() throws Exception {
        // Giả lập kết nối idle > 90s nhưng vẫn đều đặn gửi JSON ping mỗi 25s -> kết nối phải sống nguyên vẹn.
        TestWsClient client = connect(alice.token);
        try {
            for (int i = 0; i < 4; i++) {
                clock.advance(Duration.ofSeconds(25));
                client.sendText("{\"type\":\"ping\"}");
                JsonNode pong = client.awaitMessage(5);
                assertNotNull(pong, "Lần ping thứ " + (i + 1) + " phải nhận được pong");
                assertEquals("pong", pong.get("type").asText());
                wsRegistry.reapStaleAndExpiredSessions();
                assertFalse(client.closed.isDone(), "Socket khỏe có heartbeat 25s không được bị đóng");
            }

            // Ngừng ping và tua thêm 80s (> 75s idle timeout) -> reaper phải đóng kết nối.
            clock.advance(Duration.ofSeconds(80));
            wsRegistry.reapStaleAndExpiredSessions();
            assertNotNull(client.closed.get(5, TimeUnit.SECONDS));
        } finally {
            client.close();
        }

        // Mở kết nối mới rồi thu hồi thiết bị trong DB -> lần JSON ping kế tiếp phải đóng ngay với mã 4401.
        clock.resetToNow();
        TestWsClient revokedClient = connect(alice.token);
        try {
            jdbc.update("UPDATE user_devices SET revoked_at = now() WHERE id = ?", alice.deviceRowId);
            revokedClient.sendText("{\"type\":\"ping\"}");
            int closeCode = revokedClient.closed.get(5, TimeUnit.SECONDS);
            assertEquals(4401, closeCode);
        } finally {
            revokedClient.close();
        }
    }

    @Test
    void deliversAfterCommitMessageAndThrottlesTypingWithin3Seconds() throws Exception {
        String convId = openDirect(alice.token, bob.id);
        TestUser charlie = createActiveUser("Charlie");

        TestWsClient aliceWs = connect(alice.token);
        TestWsClient bobWs = connect(bob.token);
        TestWsClient charlieWs = connect(charlie.token);
        try {
            // Gửi tin REST -> Bob nhận message.new mang syncEventId khớp CSDL.
            String clientId = UUID.randomUUID().toString();
            HttpResponse<String> resp = postJson(
                    "/api/conversations/" + convId + "/messages",
                    alice.token,
                    Map.of("clientId", clientId, "type", "TEXT", "text", "Xin chao WS"));
            assertEquals(201, resp.statusCode());

            JsonNode bobFrame = bobWs.awaitType("message.new", 5);
            assertNotNull(bobFrame);
            assertTrue(bobFrame.get("syncEventId").asLong() > 0);
            assertEquals("Xin chao WS", bobFrame.path("payload").path("text").asText());

            // Alice gửi 2 lần typing.start liên tiếp trong < 3s -> Bob chỉ nhận ĐÚNG 1 khung typing (không có syncEventId).
            String typingFrame = json.writeValueAsString(Map.of(
                    "type", "typing.start",
                    "payload", Map.of("conversationId", convId)));
            aliceWs.sendText(typingFrame);
            JsonNode firstTyping = bobWs.awaitType("typing", 5);
            assertNotNull(firstTyping);
            assertTrue(firstTyping.path("syncEventId").isMissingNode() || firstTyping.path("syncEventId").isNull());
            assertEquals("start", firstTyping.path("payload").path("state").asText());

            aliceWs.sendText(typingFrame);
            assertNull(bobWs.awaitType("typing", 1), "typing.start thứ 2 trong vòng 3 giây phải bị chặn throttle");

            // Người ngoài nhóm (Charlie) gửi typing.start vào phòng Alice-Bob -> Bob không nhận được gì.
            charlieWs.sendText(typingFrame);
            assertNull(bobWs.awaitType("typing", 1));
        } finally {
            aliceWs.close();
            bobWs.close();
            charlieWs.close();
        }
    }

    @Test
    void rollbackDoesNotDispatchWebSocketFrame() throws Exception {
        TestWsClient bobWs = connect(bob.token);
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(status -> {
                syncEventWriter.write(bob.id, "CONTACT_UPDATED", "CONTACT", alice.id.toString(), Map.of("removed", false));
                // Ép rollback transaction -> callback afterCommit tuyệt đối không được chạy.
                status.setRollbackOnly();
            });
            assertNull(bobWs.awaitMessage(1), "Transaction bị rollback không được đẩy bất kỳ khung WS nào");
        } finally {
            bobWs.close();
        }
    }

    @Test
    void revokeDeviceAndLogoutCloseWebSocketWith4401() throws Exception {
        // Alice có 2 thiết bị A và B; thiết bị B mở WS, thiết bị A gọi thu hồi B -> B nhận auth.revoked và đóng 4401.
        DeviceCreds deviceB = addDeviceForUser(alice.id);
        TestWsClient wsB = connect(deviceB.token);
        try {
            HttpResponse<String> revokeResp = deleteAuth("/api/account/devices/" + deviceB.deviceRowId, alice.token);
            assertEquals(204, revokeResp.statusCode());

            JsonNode revokedFrame = wsB.awaitType("auth.revoked", 5);
            assertNotNull(revokedFrame);
            assertEquals(4401, wsB.closed.get(5, TimeUnit.SECONDS));
        } finally {
            wsB.close();
        }

        // Bob mở WS rồi gọi POST /api/auth/logout -> socket của Bob bị đóng ngay với mã 4401.
        TestWsClient bobWs = connect(bob.token);
        try {
            HttpResponse<String> logoutResp = postJson("/api/auth/logout", bob.token, Map.of());
            assertEquals(204, logoutResp.statusCode());
            assertEquals(4401, bobWs.closed.get(5, TimeUnit.SECONDS));
        } finally {
            bobWs.close();
        }
    }

    private void assertHandshakeFails(URI uri, String bearerToken, int expectedStatus) throws Exception {
        WebSocket.Builder builder = http.newWebSocketBuilder();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        CompletableFuture<WebSocket> future = builder.buildAsync(uri, new WebSocket.Listener() { });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.net.http.WebSocketHandshakeException.class, ex.getCause());
        java.net.http.WebSocketHandshakeException hse = (java.net.http.WebSocketHandshakeException) ex.getCause();
        assertEquals(expectedStatus, hse.getResponse().statusCode());
    }

    private TestWsClient connect(String token) throws Exception {
        TestWsClient client = new TestWsClient(json);
        WebSocket ws = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(wsUri("/ws"), client)
                .get(5, TimeUnit.SECONDS);
        client.ws = ws;
        return client;
    }

    private URI wsUri(String pathAndQuery) {
        return URI.create("ws://localhost:" + port + pathAndQuery);
    }

    private String openDirect(String token, UUID peerId) throws Exception {
        HttpResponse<String> resp = postJson("/api/conversations/direct", token, Map.of("userId", peerId.toString()));
        assertEquals(200, resp.statusCode());
        return json.readTree(resp.body()).get("id").asText();
    }

    private HttpResponse<String> postJson(String path, String token, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteAuth(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TestUser createActiveUser(String name) {
        UUID userId = UUID.randomUUID();
        String phone = "+849" + randomDigits(8);
        jdbc.update(
                "INSERT INTO users (id, phone_e164, password_hash, display_name, status, allow_messages_from_non_contacts) "
                        + "VALUES (?, ?, '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', ?, 'ACTIVE', TRUE)",
                userId, phone, name);
        DeviceCreds creds = addDeviceForUser(userId);
        return new TestUser(userId, creds.deviceRowId, creds.token);
    }

    private DeviceCreds addDeviceForUser(UUID userId) {
        UUID rowId = UUID.randomUUID();
        String hash = rowId.toString().replace("-", "") + rowId.toString().replace("-", "");
        jdbc.update(
                "INSERT INTO user_devices (id, user_id, device_id_hash, device_name, trusted, token_valid_after) "
                        + "VALUES (?, ?, ?, 'WsPhone', TRUE, ?)",
                rowId, userId, hash, Timestamp.from(Instant.now().minusSeconds(5)));
        return new DeviceCreds(rowId, jwt.issueAccessToken(userId, hash));
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) s = s + "8";
        return s.substring(0, len);
    }

    private record DeviceCreds(UUID deviceRowId, String token) {
    }

    private record TestUser(UUID id, UUID deviceRowId, String token) {
    }

    /** Client WebSocket hỗ trợ chờ từng khung JSON và ghi nhận mã đóng từ server. */
    private static final class TestWsClient implements WebSocket.Listener {
        private final ObjectMapper json;
        private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final AtomicInteger closeCode = new AtomicInteger(-1);
        volatile WebSocket ws;

        TestWsClient(ObjectMapper json) {
            this.json = json;
        }

        void sendText(String payload) {
            ws.sendText(payload, true).join();
        }

        JsonNode awaitMessage(long timeoutSeconds) throws InterruptedException {
            return incoming.poll(timeoutSeconds, TimeUnit.SECONDS);
        }

        JsonNode awaitType(String expectedType, long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                long remainMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                JsonNode node = incoming.poll(remainMs, TimeUnit.MILLISECONDS);
                if (node == null) {
                    return null;
                }
                if (expectedType.equals(node.path("type").asText())) {
                    return node;
                }
            }
            return null;
        }

        void close() {
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try {
                    incoming.offer(json.readTree(buffer.toString()));
                } catch (Exception ignored) {
                }
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.set(statusCode);
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }
    }
}
