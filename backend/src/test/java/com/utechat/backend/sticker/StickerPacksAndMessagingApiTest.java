package com.utechat.backend.sticker;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.ResultActions;

/** Kiểm thử danh mục, quyền tải ảnh và gửi nhãn dán qua HTTP với PostgreSQL nhúng. */
@SpringBootTest
@AutoConfigureMockMvc
class StickerPacksAndMessagingApiTest {
    private static final EmbeddedPostgres PG = startPostgres();
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger();
    private static final String DAILY = "11111111-1111-4111-8111-111111111101";
    private static final String CAMPUS = "11111111-1111-4111-8111-111111111102";
    private static final String COFFEE = "22222222-2222-4222-8222-222222220202";
    private static final String PACKS = "/api/stickers/packs";
    private static final String ASSETS = "/api/stickers/assets/";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;

    private User alice;
    private User bob;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        // Cả ứng dụng và Flyway dùng cùng DB tạm của bộ kiểm thử.
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.flyway.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/migration");
        r.add("jwt.secret", () -> "phase7-sticker-integration-test-only-example-secret");
    }

    @BeforeEach
    void createUsers() {
        alice = createUser("Alice");
        bob = createUser("Bob");
    }

    @Test
    void installsAndRemovesPacksPerAccountIncludingDefaultPack() throws Exception {
        packs(alice).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(DAILY))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].installed").value(true))
                .andExpect(jsonPath("$[0].stickers.length()").value(6))
                .andExpect(jsonPath("$[1].id").value(CAMPUS))
                .andExpect(jsonPath("$[1].installed").value(false))
                .andExpect(jsonPath("$[1].stickers.length()").value(6));

        // Hai lần thêm bộ cho cùng tài khoản chỉ lưu một lựa chọn.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(PACKS + "/" + CAMPUS + "/install").header("Authorization", alice.bearer()))
                    .andExpect(status().isNoContent());
        }
        packs(alice).andExpect(jsonPath("$[1].installed").value(true));
        packs(bob).andExpect(jsonPath("$[1].installed").value(false));
        mvc.perform(delete(PACKS + "/" + CAMPUS + "/install").header("Authorization", alice.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete(PACKS + "/" + DAILY + "/install").header("Authorization", alice.bearer()))
                .andExpect(status().isNoContent());
        packs(alice).andExpect(jsonPath("$[0].installed").value(false))
                .andExpect(jsonPath("$[1].installed").value(false));
        mvc.perform(post(PACKS + "/" + UUID.randomUUID() + "/install").header("Authorization", alice.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesOnlyAuthenticatedAllowlistedImages() throws Exception {
        mvc.perform(get(ASSETS + "ute_daily/hi.png")).andExpect(status().isUnauthorized());
        byte[] png = mvc.perform(get(ASSETS + "ute_daily/hi.png").header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(HexFormat.of().parseHex("89504e470d0a1a0a"), Arrays.copyOf(png, 8));
        mvc.perform(get(ASSETS + "ute_daily/application.yml").header("Authorization", alice.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ASSETS + "ute_daily/missing.png").header("Authorization", alice.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendsStickerIdempotentlyAndKeepsHistoryAfterUninstall() throws Exception {
        String path = messagesPath();
        String clientId = UUID.randomUUID().toString();
        Map<String, String> request = Map.of("type", "STICKER", "clientId", clientId, "stickerId", COFFEE);

        // Gỡ bộ trước khi kết nối lại vẫn cho gửi tin đã xếp hàng với mã nhãn dán hợp lệ.
        mvc.perform(delete(PACKS + "/" + CAMPUS + "/install").header("Authorization", alice.bearer()))
                .andExpect(status().isNoContent());
        var first = send(path, request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sticker.id").value(COFFEE))
                .andExpect(jsonPath("$.sticker.packId").value(CAMPUS))
                .andExpect(jsonPath("$.sticker.imageUrl").value(ASSETS + "ute_campus/coffee.png"))
                .andReturn();
        long messageId = json.readTree(first.getResponse().getContentAsByteArray()).get("id").asLong();

        send(path, request).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(messageId));
        var history = mvc.perform(get(path).header("Authorization", bob.bearer()))
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(json.readTree(history.getResponse().getContentAsByteArray())
                .findValuesAsText("imageUrl").contains(ASSETS + "ute_campus/coffee.png"));

        mvc.perform(post(path + "/" + messageId + "/recall").header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recalledAt").exists())
                .andExpect(jsonPath("$.sticker").doesNotExist());
    }

    @Test
    void rejectsUnknownStickerAndConflictingPayloadFields() throws Exception {
        String path = messagesPath();
        send(path, Map.of("type", "STICKER", "clientId", UUID.randomUUID().toString(),
                "stickerId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        send(path, Map.of("type", "STICKER", "clientId", UUID.randomUUID().toString(),
                "stickerId", COFFEE, "text", "invalid")).andExpect(status().isBadRequest());
        send(path, Map.of("type", "TEXT", "clientId", UUID.randomUUID().toString(),
                "stickerId", COFFEE, "text", "invalid")).andExpect(status().isBadRequest());
    }

    private ResultActions packs(User user) throws Exception {
        return mvc.perform(get(PACKS).header("Authorization", user.bearer()));
    }

    private ResultActions send(String path, Map<String, String> payload) throws Exception {
        return mvc.perform(post(path).header("Authorization", alice.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)));
    }

    private String messagesPath() throws Exception {
        var response = mvc.perform(post("/api/conversations/direct").header("Authorization", alice.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("userId", bob.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsByteArray()).get("id").asText();
        return "/api/conversations/" + id + "/messages";
    }

    private User createUser(String name) {
        // Chèn tài khoản mới và thiết bị đã xác thực để thử policy JWT thực qua bộ lọc HTTP.
        UUID id = UUID.randomUUID();
        String part = id.toString().replace("-", "");
        String deviceHash = part + part;
        jdbc.update("""
                INSERT INTO public.users (id, phone_e164, password_hash, display_name, status,
                                          allow_messages_from_non_contacts)
                VALUES (?, ?, 'fixture-password-never-used', ?, 'ACTIVE', TRUE)
                """, id, "+8499%07d".formatted(PHONE_SEQ.incrementAndGet()), name);
        jdbc.update("""
                INSERT INTO public.user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after)
                VALUES (?, ?, 'TestPhone', TRUE, ?)
                """, id, deviceHash, Timestamp.from(Instant.now().minusSeconds(10)));
        return new User(id, "Bearer " + jwt.issueAccessToken(id, deviceHash));
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private record User(UUID id, String bearer) {}
}
