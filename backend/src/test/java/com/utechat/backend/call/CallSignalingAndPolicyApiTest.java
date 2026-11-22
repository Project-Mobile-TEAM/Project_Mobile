package com.utechat.backend.call;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.service.CallService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Kiểm chứng tích hợp toàn diện hệ thống cuộc gọi thoại 1-1 trên EmbeddedPostgres + Tomcat WebSocket thật (Plan 06-02):
 * 1. Thứ tự chính sách: 400 CALL_UNSUPPORTED (GROUP / non-VOICE), 403 BLOCKED trước 403 CONTACT_REQUIRED, 429 RATE_LIMITED (5/phút).
 * 2. Chống gọi chồng đồng thời: 409 CALL_BUSY không tạo dòng calls và không đổ chuông.
 * 3. Tính nguyên tử accept/reject/end, hết hạn chuông 45s (kể cả đúng mốc biên), liveness lease 60s khi ACTIVE (reconnect, 3rd device, một bên rớt mạng) và duy nhất 1 tin nhắn CALL.
 * 4. Đa thiết bị: call.incoming tới mọi thiết bị callee, call.handled_elsewhere chỉ tới thiết bị khác, và khóa báo hiệu 2 thiết bị cho call.offer/answer/ice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CallSignalingAndPolicyApiTest {
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
        r.add("jwt.secret", () -> "utechat-phase6-call-test-secret-0123456789abcdef");
        r.add("app.coturn.urls", () -> "stun:turn.utechat.test:3478,turns:turn.utechat.test:443?transport=tcp");
        r.add("app.coturn.shared-secret", () -> "phase6-test-coturn-shared-secret");
        r.add("app.coturn.ttl-seconds", () -> "3600");
    }

    /** Đồng hồ giả lập cho phép tua thời gian chính xác tới mốc 45s và 60s mà không cần Thread.sleep. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

        void resetToNow() {
            current.set(Instant.now());
        }

        void advance(Duration d) {
            current.updateAndGet(t -> t.plus(d));
        }

        void setInstant(Instant instant) {
            current.set(instant);
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
    @Autowired private CallService callService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        clock.resetToNow();
    }

    @Test
    void callPolicyOrderGroupBlockedContactRequiredAndRateLimit() throws Exception {
        TestUser alice = createActiveUser("Alice");
        TestUser bob = createActiveUser("Bob");
        TestUser charlie = createActiveUser("Charlie");

        // 1. Cuộc gọi trên hội thoại GROUP -> 400 CALL_UNSUPPORTED.
        makeFriends(alice.id, bob.id);
        makeFriends(alice.id, charlie.id);
        HttpResponse<String> groupResp = postJson(
                "/api/conversations/group",
                alice.token,
                Map.of("title", "Nhom 6", "memberIds", List.of(bob.id.toString(), charlie.id.toString())));
        assertEquals(201, groupResp.statusCode());
        String groupConvId = json.readTree(groupResp.body()).get("id").asText();

        HttpResponse<String> groupCallResp = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", groupConvId, "media", "VOICE"));
        assertEquals(400, groupCallResp.statusCode());
        assertEquals("CALL_UNSUPPORTED", errorCode(groupCallResp));

        // 2. Người lạ (dù allow_messages_from_non_contacts = TRUE) -> 403 CONTACT_REQUIRED.
        TestUser stranger = createActiveUser("Stranger");
        String strangerConvId = openDirect(alice.token, stranger.id);
        HttpResponse<String> strangerCall = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", strangerConvId, "media", "VOICE"));
        assertEquals(403, strangerCall.statusCode());
        assertEquals("CONTACT_REQUIRED", errorCode(strangerCall));

        // 3. Bị chặn (BLOCKED) phải được ưu tiên trả 403 BLOCKED TRƯỚC 403 CONTACT_REQUIRED (kể cả chưa là bạn bè!).
        blockUser(stranger.id, alice.id);
        HttpResponse<String> blockedStrangerCall = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", strangerConvId, "media", "VOICE"));
        assertEquals(403, blockedStrangerCall.statusCode());
        assertEquals("BLOCKED", errorCode(blockedStrangerCall));

        // Bạn bè nhưng một bên chặn bên kia -> 403 BLOCKED.
        String directConvId = openDirect(alice.token, bob.id);
        // Video không được hỗ trợ -> 400 CALL_UNSUPPORTED.
        HttpResponse<String> videoCall = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", directConvId, "media", "VIDEO"));
        assertEquals(400, videoCall.statusCode());
        assertEquals("CALL_UNSUPPORTED", errorCode(videoCall));

        blockUser(bob.id, alice.id);
        HttpResponse<String> blockedFriendCall = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", directConvId, "media", "VOICE"));
        assertEquals(403, blockedFriendCall.statusCode());
        assertEquals("BLOCKED", errorCode(blockedFriendCall));
        unblockUser(bob.id, alice.id);

        // 4. Bạn bè ACCEPTED không bị chặn -> 201 RINGING kèm iceServers[]; lần gọi thứ 6 trong 60s -> 429 RATE_LIMITED.
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> startResp = postJson(
                    "/api/calls",
                    alice.token,
                    Map.of("conversationId", directConvId, "media", "VOICE"));
            assertEquals(201, startResp.statusCode(), "Lần gọi thứ " + (i + 1) + " phải thành công 201");
            JsonNode body = json.readTree(startResp.body());
            assertEquals("RINGING", body.get("state").asText());
            assertNotNull(body.get("expiresAt").asText());
            assertTrue(body.get("iceServers").isArray() && body.get("iceServers").size() >= 1);
            assertFalse(startResp.body().contains("phase6-test-coturn-shared-secret"),
                    "Tuyệt đối không lộ COTURN_SHARED_SECRET trong response");

            // Hủy cuộc gọi để giải phóng bận cho lượt kế tiếp trong cùng cửa sổ 60s.
            String callId = body.get("callId").asText();
            HttpResponse<String> endResp = postJson("/api/calls/" + callId + "/end", alice.token, Map.of());
            assertEquals(204, endResp.statusCode());
        }

        HttpResponse<String> sixthCall = postJson(
                "/api/calls",
                alice.token,
                Map.of("conversationId", directConvId, "media", "VOICE"));
        assertEquals(429, sixthCall.statusCode());
        assertEquals("RATE_LIMITED", errorCode(sixthCall));
        assertTrue(json.readTree(sixthCall.body()).path("error").path("details").path("retryAfterSeconds").asLong() >= 1L);
    }

    @Test
    void concurrentStartCallEnforces409CallBusyWithoutRinging() throws Exception {
        TestUser alice = createActiveUser("Alice");
        TestUser bob = createActiveUser("Bob");
        TestUser charlie = createActiveUser("Charlie");
        makeFriends(alice.id, bob.id);
        makeFriends(charlie.id, bob.id);
        String aliceBobConv = openDirect(alice.token, bob.id);
        String charlieBobConv = openDirect(charlie.token, bob.id);

        TestWsClient bobWs = connect(bob.token);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<HttpResponse<String>> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/calls", alice.token, Map.of("conversationId", aliceBobConv, "media", "VOICE"));
            });
            Future<HttpResponse<String>> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/calls", charlie.token, Map.of("conversationId", charlieBobConv, "media", "VOICE"));
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            HttpResponse<String> r1 = f1.get(10, TimeUnit.SECONDS);
            HttpResponse<String> r2 = f2.get(10, TimeUnit.SECONDS);

            List<HttpResponse<String>> responses = List.of(r1, r2);
            HttpResponse<String> winner = responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElse(null);
            HttpResponse<String> loser = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElse(null);

            assertNotNull(winner, "Phải có đúng 1 cuộc gọi thắng 201");
            assertNotNull(loser, "Cuộc gọi còn lại phải nhận 409 CALL_BUSY");
            assertEquals("CALL_BUSY", errorCode(loser));

            // Chỉ có ĐÚNG 1 dòng calls cho Bob và Bob chỉ nhận ĐÚNG 1 khung call.incoming.
            Integer callRows = jdbc.queryForObject(
                    "SELECT count(*) FROM public.calls WHERE callee_id = ?",
                    Integer.class,
                    bob.id);
            assertEquals(1, callRows);

            JsonNode firstIncoming = bobWs.awaitType("call.incoming", 5);
            assertNotNull(firstIncoming);
            String winningCallId = json.readTree(winner.body()).get("callId").asText();
            assertEquals(winningCallId, firstIncoming.path("payload").path("callId").asText());
            assertNull(bobWs.awaitType("call.incoming", 1), "Không được phát thêm call.incoming cho lệnh bị 409 CALL_BUSY");

            // Khi Bob chấp nhận thành ACTIVE, cuộc gọi khác tới Bob vẫn phải nhận 409 CALL_BUSY và không tạo dòng mới.
            HttpResponse<String> acceptResp = postJson("/api/calls/" + winningCallId + "/accept", bob.token, Map.of());
            assertEquals(200, acceptResp.statusCode());

            TestUser dave = createActiveUser("Dave");
            makeFriends(dave.id, bob.id);
            String daveBobConv = openDirect(dave.token, bob.id);
            HttpResponse<String> busyOnActive = postJson(
                    "/api/calls",
                    dave.token,
                    Map.of("conversationId", daveBobConv, "media", "VOICE"));
            assertEquals(409, busyOnActive.statusCode());
            assertEquals("CALL_BUSY", errorCode(busyOnActive));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.calls WHERE callee_id = ?", Integer.class, bob.id));
            assertNull(bobWs.awaitType("call.incoming", 1));
        } finally {
            pool.shutdownNow();
            bobWs.close();
        }
    }

    @Test
    void atomicAcceptRejectAnd45sTimeoutWithSingleCallMessage() throws Exception {
        TestUser alice = createActiveUser("Alice");
        TestUser bob = createActiveUser("Bob");
        makeFriends(alice.id, bob.id);
        String convId = openDirect(alice.token, bob.id);

        // --- Phần A: Đua đồng thời POST /accept vs POST /reject -> đúng 1 bên 2xx, bên còn lại 409 INVALID_STATE ---
        HttpResponse<String> start1 = postJson("/api/calls", alice.token, Map.of("conversationId", convId, "media", "VOICE"));
        assertEquals(201, start1.statusCode());
        String callId1 = json.readTree(start1.body()).get("callId").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<HttpResponse<String>> acceptFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/calls/" + callId1 + "/accept", bob.token, Map.of());
            });
            Future<HttpResponse<String>> rejectFuture = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return postJson("/api/calls/" + callId1 + "/reject", bob.token, Map.of());
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            HttpResponse<String> acceptResp = acceptFuture.get(10, TimeUnit.SECONDS);
            HttpResponse<String> rejectResp = rejectFuture.get(10, TimeUnit.SECONDS);

            boolean acceptWon = acceptResp.statusCode() == 200 && rejectResp.statusCode() == 409;
            boolean rejectWon = rejectResp.statusCode() == 204 && acceptResp.statusCode() == 409;
            assertTrue(acceptWon || rejectWon,
                    "Đúng 1 lệnh thắng 2xx và lệnh kia 409 INVALID_STATE; accept=" + acceptResp.statusCode() + ", reject=" + rejectResp.statusCode());
            if (acceptWon) {
                assertEquals("INVALID_STATE", errorCode(rejectResp));
                // Kết thúc cuộc gọi rồi lặp POST /end nhiều lần -> luôn 204 idempotent và chỉ có ĐÚNG 1 tin CALL.
                assertEquals(204, postJson("/api/calls/" + callId1 + "/end", alice.token, Map.of()).statusCode());
                assertEquals(204, postJson("/api/calls/" + callId1 + "/end", bob.token, Map.of()).statusCode());
            } else {
                assertEquals("INVALID_STATE", errorCode(acceptResp));
                // Lặp POST /reject và POST /end trên cuộc gọi đã từ chối -> 204 idempotent, chỉ ĐÚNG 1 tin CALL.
                assertEquals(204, postJson("/api/calls/" + callId1 + "/reject", bob.token, Map.of()).statusCode());
                assertEquals(204, postJson("/api/calls/" + callId1 + "/end", alice.token, Map.of()).statusCode());
            }
            assertEquals(1, countCallMessages(callId1));
        } finally {
            pool.shutdownNow();
        }

        // --- Phần B: Quá hạn 45s (46s) -> sweepExpiredCalls() chuyển MISSED/TIMEOUT, phát call.ended cho 2 bên, 1 tin CALL ---
        TestWsClient aliceWs = connect(alice.token);
        TestWsClient bobWs = connect(bob.token);
        try {
            HttpResponse<String> start2 = postJson("/api/calls", alice.token, Map.of("conversationId", convId, "media", "VOICE"));
            assertEquals(201, start2.statusCode());
            String callId2 = json.readTree(start2.body()).get("callId").asText();
            assertNotNull(bobWs.awaitType("call.incoming", 5));

            clock.advance(Duration.ofSeconds(46));
            callService.sweepExpiredCalls();

            JsonNode aliceEnded = aliceWs.awaitType("call.ended", 5);
            JsonNode bobEnded = bobWs.awaitType("call.ended", 5);
            assertNotNull(aliceEnded);
            assertNotNull(bobEnded);
            assertEquals("MISSED", aliceEnded.path("payload").path("state").asText());
            assertEquals("TIMEOUT", aliceEnded.path("payload").path("endReason").asText());
            assertEquals("MISSED", bobEnded.path("payload").path("state").asText());
            // Payload MISSED phải mang kèm thông tin caller để Android NotificationHelper dựng thông báo gọi nhỡ kể cả khác batch /sync.
            assertEquals(alice.id.toString(), bobEnded.path("payload").path("caller").path("id").asText());
            assertEquals("Alice", bobEnded.path("payload").path("caller").path("displayName").asText());
            String storedMissedPayload = jdbc.queryForObject(
                    "SELECT payload_json::text FROM public.sync_events "
                            + "WHERE user_id = ? AND event_type = 'CALL_UPDATED' AND entity_id = ? "
                            + "ORDER BY id DESC LIMIT 1",
                    String.class,
                    bob.id, callId2);
            JsonNode storedMissedNode = json.readTree(storedMissedPayload);
            assertEquals("MISSED", storedMissedNode.path("state").asText());
            assertEquals(alice.id.toString(), storedMissedNode.path("caller").path("id").asText());
            assertEquals("Alice", storedMissedNode.path("caller").path("displayName").asText());
            assertEquals(1, countCallMessages(callId2));

            // --- Phần C: Đúng mốc biên expires_at (45.000s) -> từ chối accept/reject/end muộn (409 INVALID_STATE), sweeper ghi đúng 1 MISSED ---
            clock.advance(Duration.ofSeconds(65)); // Reset cửa sổ rate limit 60s.
            HttpResponse<String> start3 = postJson("/api/calls", alice.token, Map.of("conversationId", convId, "media", "VOICE"));
            assertEquals(201, start3.statusCode());
            JsonNode start3Body = json.readTree(start3.body());
            String callId3 = start3Body.get("callId").asText();
            Instant exactDeadline = Instant.parse(start3Body.get("expiresAt").asText());
            assertNotNull(bobWs.awaitType("call.incoming", 5));

            clock.setInstant(exactDeadline);
            HttpResponse<String> lateAccept = postJson("/api/calls/" + callId3 + "/accept", bob.token, Map.of());
            HttpResponse<String> lateReject = postJson("/api/calls/" + callId3 + "/reject", bob.token, Map.of());
            HttpResponse<String> lateCancel = postJson("/api/calls/" + callId3 + "/end", alice.token, Map.of());
            assertEquals(409, lateAccept.statusCode());
            assertEquals("INVALID_STATE", errorCode(lateAccept));
            assertEquals(409, lateReject.statusCode());
            assertEquals("INVALID_STATE", errorCode(lateReject));
            assertEquals(409, lateCancel.statusCode());
            assertEquals("INVALID_STATE", errorCode(lateCancel));

            callService.sweepExpiredCalls();
            String state3 = jdbc.queryForObject(
                    "SELECT state FROM public.calls WHERE id = ?::uuid",
                    String.class,
                    callId3);
            assertEquals("MISSED", state3);
            assertEquals(1, countCallMessages(callId3));
            assertNotNull(aliceWs.awaitType("call.ended", 5));
            assertNotNull(bobWs.awaitType("call.ended", 5));
        } finally {
            aliceWs.close();
            bobWs.close();
        }

        // --- Phần D: ACTIVE liveness 60s (reconnect trong 60s gia hạn được, thiết bị thứ 3 không gia hạn được, chỉ 1 bên gia hạn vẫn FAILED/OFFLINE & giải phóng gọi mới) ---
        clock.advance(Duration.ofSeconds(65));
        DeviceCreds bobDevice2 = addDeviceForUser(bob.id);
        TestWsClient aliceWsActive = connect(alice.token);
        TestWsClient bobWsActive = connect(bob.token);
        TestWsClient bobDevice2Ws = connect(bobDevice2.token);
        try {
            HttpResponse<String> start4 = postJson("/api/calls", alice.token, Map.of("conversationId", convId, "media", "VOICE"));
            assertEquals(201, start4.statusCode());
            String callId4 = json.readTree(start4.body()).get("callId").asText();
            assertEquals(200, postJson("/api/calls/" + callId4 + "/accept", bob.token, Map.of()).statusCode());
            assertNotNull(aliceWsActive.awaitType("call.accepted", 5));

            // Ngắt kết nối WS của Alice, tua 50s (< 60s) rồi kết nối lại để gửi call.keepalive -> gia hạn thành công riêng cho Alice.
            aliceWsActive.close();
            clock.advance(Duration.ofSeconds(50));
            aliceWsActive = connect(alice.token);
            String keepaliveFrame = json.writeValueAsString(Map.of(
                    "type", "call.keepalive",
                    "payload", Map.of("callId", callId4)));
            aliceWsActive.sendText(keepaliveFrame);

            // Thiết bị thứ 3 của Bob (bobDevice2) cố gửi call.keepalive -> không được gia hạn callee_alive_until!
            Instant calleeDeadlineBefore = jdbc.queryForObject(
                    "SELECT callee_alive_until FROM public.calls WHERE id = ?::uuid",
                    Timestamp.class,
                    callId4).toInstant();
            bobDevice2Ws.sendText(keepaliveFrame);

            // Chờ ngắn để WS handler xử lý xong keepalive của Alice và bobDevice2.
            awaitCondition(() -> {
                Instant callerDeadline = jdbc.queryForObject(
                        "SELECT caller_alive_until FROM public.calls WHERE id = ?::uuid",
                        Timestamp.class,
                        callId4).toInstant();
                return callerDeadline.isAfter(calleeDeadlineBefore);
            }, 3000);

            Instant calleeDeadlineAfter = jdbc.queryForObject(
                    "SELECT callee_alive_until FROM public.calls WHERE id = ?::uuid",
                    Timestamp.class,
                    callId4).toInstant();
            assertEquals(calleeDeadlineBefore, calleeDeadlineAfter, "Thiết bị thứ 3 không được gia hạn lease của callee");

            // Tua thêm 15s (t = 65s): Alice vẫn còn hạn tới t = 110s nhưng Bob (callee) đã quá hạn t = 60s!
            // Báo hiệu phải bị chặn vì lease của callee đã hết, và sweeper phải kết thúc thành FAILED/OFFLINE.
            clock.advance(Duration.ofSeconds(15));
            aliceWsActive.sendText(json.writeValueAsString(Map.of(
                    "type", "call.ice",
                    "payload", Map.of("callId", callId4, "candidate", "candidate:expired-lease"))));
            assertNull(bobWsActive.awaitType("call.ice", 1), "Không được chuyển tiếp signaling khi một bên đã hết hạn lease");

            callService.sweepExpiredCalls();
            Map<String, Object> row4 = jdbc.queryForMap(
                    "SELECT state, end_reason FROM public.calls WHERE id = ?::uuid",
                    callId4);
            assertEquals("FAILED", row4.get("state"));
            assertEquals("OFFLINE", row4.get("end_reason"));
            assertEquals(1, countCallMessages(callId4));

            // Cả hai tài khoản được giải phóng để thực hiện cuộc gọi mới thành công (201).
            HttpResponse<String> nextCall = postJson("/api/calls", alice.token, Map.of("conversationId", convId, "media", "VOICE"));
            assertEquals(201, nextCall.statusCode());
        } finally {
            aliceWsActive.close();
            bobWsActive.close();
            bobDevice2Ws.close();
        }
    }

    @Test
    void multiDeviceHandledElsewhereAndTwoDeviceSignalingLock() throws Exception {
        TestUser alice = createActiveUser("Alice");
        TestUser bob = createActiveUser("Bob");
        makeFriends(alice.id, bob.id);
        String convId = openDirect(alice.token, bob.id);

        DeviceCreds bobDevice2 = addDeviceForUser(bob.id);
        TestWsClient aliceWs = connect(alice.token);
        TestWsClient bobDev1Ws = connect(bob.token);
        TestWsClient bobDev2Ws = connect(bobDevice2.token);
        try {
            // 1. Caller gọi -> cả 2 thiết bị của Callee đều nhận call.incoming mang syncEventId và expiresAt.
            HttpResponse<String> startResp = postJson(
                    "/api/calls",
                    alice.token,
                    Map.of("conversationId", convId, "media", "VOICE"));
            assertEquals(201, startResp.statusCode());
            JsonNode startBody = json.readTree(startResp.body());
            String callId = startBody.get("callId").asText();
            String expectedExpiresAt = startBody.get("expiresAt").asText();

            JsonNode inc1 = bobDev1Ws.awaitType("call.incoming", 5);
            JsonNode inc2 = bobDev2Ws.awaitType("call.incoming", 5);
            assertNotNull(inc1);
            assertNotNull(inc2);
            assertTrue(inc1.get("syncEventId").asLong() > 0);
            assertTrue(inc2.get("syncEventId").asLong() > 0);
            assertEquals(expectedExpiresAt, inc1.path("payload").path("expiresAt").asText());
            assertEquals(expectedExpiresAt, inc2.path("payload").path("expiresAt").asText());

            // Khi còn RINGING (chưa ACTIVE), call.offer phải bị bỏ qua lặng lẽ.
            aliceWs.sendText(json.writeValueAsString(Map.of(
                    "type", "call.offer",
                    "payload", Map.of("callId", callId, "sdp", "v=0 premature-offer"))));
            assertNull(bobDev1Ws.awaitType("call.offer", 1));
            assertNull(bobDev2Ws.awaitType("call.offer", 1));

            // 2. Callee Device 1 bấm chấp nhận -> Caller nhận call.accepted; Device 2 nhận call.handled_elsewhere; Device 1 KHÔNG nhận.
            HttpResponse<String> acceptResp = postJson("/api/calls/" + callId + "/accept", bob.token, Map.of());
            assertEquals(200, acceptResp.statusCode());

            // Gọi lại /accept từ chính Device 1 -> 200 OK idempotent.
            assertEquals(200, postJson("/api/calls/" + callId + "/accept", bob.token, Map.of()).statusCode());
            // Thiết bị thứ 2 gọi /accept khi đã ACTIVE -> 409 INVALID_STATE.
            HttpResponse<String> acceptFromDev2 = postJson("/api/calls/" + callId + "/accept", bobDevice2.token, Map.of());
            assertEquals(409, acceptFromDev2.statusCode());
            assertEquals("INVALID_STATE", errorCode(acceptFromDev2));

            JsonNode accepted = aliceWs.awaitType("call.accepted", 5);
            assertNotNull(accepted);
            assertEquals(callId, accepted.path("payload").path("callId").asText());

            JsonNode handledElsewhere = bobDev2Ws.awaitType("call.handled_elsewhere", 5);
            assertNotNull(handledElsewhere);
            assertTrue(handledElsewhere.get("syncEventId").asLong() > 0);
            assertEquals(callId, handledElsewhere.path("payload").path("callId").asText());
            assertNull(bobDev1Ws.awaitType("call.handled_elsewhere", 1),
                    "Thiết bị đã nhận máy (Device 1) không được nhận call.handled_elsewhere");

            // Ghi nhận số sync_events trước khi trao đổi signaling để đảm bảo signaling không ghi sync_events.
            long syncEventsBeforeSignaling = jdbc.queryForObject("SELECT count(*) FROM public.sync_events", Long.class);

            // 3. Caller gửi call.offer -> CHỈ chuyển tới Callee Device 1 (KHÔNG chuyển tới Device 2), syncEventId = null.
            aliceWs.sendText(json.writeValueAsString(Map.of(
                    "type", "call.offer",
                    "payload", Map.of("callId", callId, "sdp", "v=0 offer-sdp-body"))));
            JsonNode offerOnDev1 = bobDev1Ws.awaitType("call.offer", 5);
            assertNotNull(offerOnDev1);
            assertTrue(offerOnDev1.path("syncEventId").isMissingNode() || offerOnDev1.path("syncEventId").isNull());
            assertEquals("v=0 offer-sdp-body", offerOnDev1.path("payload").path("sdp").asText());
            assertNull(bobDev2Ws.awaitType("call.offer", 1), "Device 2 không được nghe lén call.offer");

            // 4. Callee Device 2 (thiết bị thứ 3) cố tiêm call.answer và call.ice -> bị khóa 2 thiết bị chặn hoàn toàn.
            bobDev2Ws.sendText(json.writeValueAsString(Map.of(
                    "type", "call.answer",
                    "payload", Map.of("callId", callId, "sdp", "v=0 spoofed-answer"))));
            bobDev2Ws.sendText(json.writeValueAsString(Map.of(
                    "type", "call.ice",
                    "payload", Map.of("callId", callId, "candidate", "candidate:spoofed"))));
            assertNull(aliceWs.awaitType("call.answer", 1), "Không được chuyển tiếp call.answer từ thiết bị thứ 3");
            assertNull(aliceWs.awaitType("call.ice", 1), "Không được chuyển tiếp call.ice từ thiết bị thứ 3");

            // 5. Callee Device 1 gửi call.answer và call.ice -> chuyển tiếp sạch sang Caller Device 1.
            bobDev1Ws.sendText(json.writeValueAsString(Map.of(
                    "type", "call.answer",
                    "payload", Map.of("callId", callId, "sdp", "v=0 real-answer-sdp"))));
            bobDev1Ws.sendText(json.writeValueAsString(Map.of(
                    "type", "call.ice",
                    "payload", Map.of("callId", callId, "candidate", "candidate:real-1"))));

            JsonNode answerOnCaller = aliceWs.awaitType("call.answer", 5);
            assertNotNull(answerOnCaller);
            assertTrue(answerOnCaller.path("syncEventId").isMissingNode() || answerOnCaller.path("syncEventId").isNull());
            assertEquals("v=0 real-answer-sdp", answerOnCaller.path("payload").path("sdp").asText());

            JsonNode iceOnCaller = aliceWs.awaitType("call.ice", 5);
            assertNotNull(iceOnCaller);
            assertTrue(iceOnCaller.path("syncEventId").isMissingNode() || iceOnCaller.path("syncEventId").isNull());
            assertEquals("candidate:real-1", iceOnCaller.path("payload").path("candidate").asText());

            long syncEventsAfterSignaling = jdbc.queryForObject("SELECT count(*) FROM public.sync_events", Long.class);
            assertEquals(syncEventsBeforeSignaling, syncEventsAfterSignaling,
                    "call.offer / call.answer / call.ice tuyệt đối không được ghi vào sync_events");

            // Thiết bị thứ 2 không được kết thúc cuộc gọi ACTIVE của Device 1.
            HttpResponse<String> endFromDev2 = postJson("/api/calls/" + callId + "/end", bobDevice2.token, Map.of());
            assertEquals(403, endFromDev2.statusCode());
            // Device 1 kết thúc sạch -> 204.
            assertEquals(204, postJson("/api/calls/" + callId + "/end", bob.token, Map.of()).statusCode());
            assertEquals(1, countCallMessages(callId));
        } finally {
            aliceWs.close();
            bobDev1Ws.close();
            bobDev2Ws.close();
        }
    }

    private int countCallMessages(String callId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM public.messages WHERE call_id = ?::uuid",
                Integer.class,
                callId);
        return count == null ? 0 : count;
    }

    private String errorCode(HttpResponse<String> resp) throws Exception {
        return json.readTree(resp.body()).path("error").path("code").asText("");
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "Điều kiện bất đồng bộ không đạt trong " + timeoutMs + "ms");
    }

    private TestWsClient connect(String token) throws Exception {
        TestWsClient client = new TestWsClient(json);
        WebSocket ws = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), client)
                .get(5, TimeUnit.SECONDS);
        client.ws = ws;
        return client;
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

    private void makeFriends(UUID a, UUID b) {
        jdbc.update(
                "INSERT INTO public.contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) "
                        + "ON CONFLICT (owner_id, contact_user_id) DO UPDATE SET is_friend = TRUE, blocked = FALSE",
                a, b);
        jdbc.update(
                "INSERT INTO public.contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, TRUE, FALSE) "
                        + "ON CONFLICT (owner_id, contact_user_id) DO UPDATE SET is_friend = TRUE, blocked = FALSE",
                b, a);
    }

    private void blockUser(UUID blocker, UUID target) {
        jdbc.update(
                "INSERT INTO public.contacts (owner_id, contact_user_id, is_friend, blocked) VALUES (?, ?, FALSE, TRUE) "
                        + "ON CONFLICT (owner_id, contact_user_id) DO UPDATE SET blocked = TRUE",
                blocker, target);
    }

    private void unblockUser(UUID blocker, UUID target) {
        jdbc.update(
                "UPDATE public.contacts SET blocked = FALSE WHERE owner_id = ? AND contact_user_id = ?",
                blocker, target);
    }

    private TestUser createActiveUser(String name) {
        UUID userId = UUID.randomUUID();
        String phone = "+849" + randomDigits(8);
        jdbc.update(
                "INSERT INTO public.users (id, phone_e164, password_hash, display_name, status, allow_messages_from_non_contacts) "
                        + "VALUES (?, ?, '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyha', ?, 'ACTIVE', TRUE)",
                userId, phone, name);
        DeviceCreds creds = addDeviceForUser(userId);
        return new TestUser(userId, creds.deviceRowId, creds.deviceHash, creds.token);
    }

    private DeviceCreds addDeviceForUser(UUID userId) {
        UUID rowId = UUID.randomUUID();
        String hash = rowId.toString().replace("-", "") + rowId.toString().replace("-", "");
        jdbc.update(
                "INSERT INTO public.user_devices (id, user_id, device_id_hash, device_name, trusted, token_valid_after) "
                        + "VALUES (?, ?, ?, 'CallPhone', TRUE, ?)",
                rowId, userId, hash, Timestamp.from(Instant.now().minusSeconds(5)));
        return new DeviceCreds(rowId, hash, jwt.issueAccessToken(userId, hash));
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) {
            s = s + "7";
        }
        return s.substring(0, len);
    }

    private record DeviceCreds(UUID deviceRowId, String deviceHash, String token) {
    }

    private record TestUser(UUID id, UUID deviceRowId, String deviceHash, String token) {
    }

    /** Client WebSocket hỗ trợ lọc và chờ từng khung JSON theo loại sự kiện. */
    private static final class TestWsClient implements WebSocket.Listener {
        private final ObjectMapper json;
        private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        volatile WebSocket ws;

        TestWsClient(ObjectMapper json) {
            this.json = json;
        }

        void sendText(String payload) {
            ws.sendText(payload, true).join();
        }

        JsonNode awaitType(String expectedType, long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            List<JsonNode> unmatched = new ArrayList<>();
            try {
                while (System.nanoTime() < deadline) {
                    long remainMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    JsonNode node = incoming.poll(remainMs, TimeUnit.MILLISECONDS);
                    if (node == null) {
                        return null;
                    }
                    if (expectedType.equals(node.path("type").asText())) {
                        return node;
                    }
                    unmatched.add(node);
                }
                return null;
            } finally {
                // Giữ lại các khung khác loại (ví dụ message.new) để không làm mất sự kiện của kiểm tra tiếp theo.
                incoming.addAll(unmatched);
            }
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
    }
}
