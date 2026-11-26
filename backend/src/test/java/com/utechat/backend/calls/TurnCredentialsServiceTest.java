package com.utechat.backend.calls;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.dto.CallDtos.IceServerDto;
import com.utechat.backend.service.TurnCredentialsService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Unit test thuần JUnit 5 cho TurnCredentialsService (D-09, ADR-013):
 * kiểm chứng thuật toán HMAC-SHA1 chuẩn coturn REST API (<expiry>:<userId> + Base64),
 * tách biệt STUN (không credential) với TURN/TURNS (kèm credential), và fallback STUN khi chưa cấu hình.
 */
class TurnCredentialsServiceTest {

    @Test
    void generatesRfcCoturnHmacSha1CredentialsAndSeparatesStunFromTurn() throws Exception {
        Instant fixedNow = Instant.parse("2026-11-22T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        String secret = "super-secret-coturn-key-for-unit-test";
        long ttlSeconds = 3600L;
        UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        TurnCredentialsService service = new TurnCredentialsService(
                fixedClock,
                "stun:turn.utechat.example:3478, turn:turn.utechat.example:3478?transport=udp, turns:turn.utechat.example:443?transport=tcp",
                secret,
                ttlSeconds,
                "stun:stun.l.google.com:19302");

        List<IceServerDto> servers = service.generateIceServers(userId);
        assertEquals(2, servers.size());

        // Mục STUN không được mang username/credential.
        IceServerDto stunEntry = servers.get(0);
        assertEquals(List.of("stun:turn.utechat.example:3478"), stunEntry.urls());
        assertNull(stunEntry.username());
        assertNull(stunEntry.credential());

        // Mục TURN/TURNS mang username = <expiry>:<userId> và credential = Base64(HMAC-SHA1(secret, username)).
        IceServerDto turnEntry = servers.get(1);
        assertEquals(
                List.of(
                        "turn:turn.utechat.example:3478?transport=udp",
                        "turns:turn.utechat.example:443?transport=tcp"),
                turnEntry.urls());

        long expectedExpiry = fixedNow.getEpochSecond() + ttlSeconds;
        String expectedUsername = expectedExpiry + ":" + userId;
        assertEquals(expectedUsername, turnEntry.username());

        // Kiểm chứng độc lập bằng Java Mac HmacSHA1.
        Mac refMac = Mac.getInstance("HmacSHA1");
        refMac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String expectedCredential = Base64.getEncoder()
                .encodeToString(refMac.doFinal(expectedUsername.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expectedCredential, turnEntry.credential());
        assertNotEquals(secret, turnEntry.credential(), "Tuyệt đối không trả trực tiếp shared secret về client");
    }

    @Test
    void fallsBackToPublicStunWhenSecretOrUrlsUnconfigured() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        UUID userId = UUID.randomUUID();

        // Thiếu shared secret -> fallback về STUN công khai không lỗi.
        TurnCredentialsService noSecret = new TurnCredentialsService(
                fixedClock,
                "turns:turn.utechat.example:443?transport=tcp",
                "   ",
                3600L,
                "stun:stun.l.google.com:19302");
        List<IceServerDto> fallback1 = noSecret.generateIceServers(userId);
        assertEquals(1, fallback1.size());
        assertEquals(List.of("stun:stun.l.google.com:19302"), fallback1.get(0).urls());
        assertNull(fallback1.get(0).username());
        assertNull(fallback1.get(0).credential());

        // Thiếu cả URLs lẫn secret -> fallback về STUN mặc định.
        TurnCredentialsService blankAll = new TurnCredentialsService(
                fixedClock,
                "",
                "",
                0L,
                "");
        List<IceServerDto> fallback2 = blankAll.generateIceServers(userId);
        assertEquals(1, fallback2.size());
        assertEquals(List.of("stun:stun.l.google.com:19302"), fallback2.get(0).urls());
        assertNull(fallback2.get(0).username());
        assertNull(fallback2.get(0).credential());
    }
}
