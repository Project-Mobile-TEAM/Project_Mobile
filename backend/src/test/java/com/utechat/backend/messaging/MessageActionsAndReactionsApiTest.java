package com.utechat.backend.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Chạy API Phase 7 qua HTTP giả lập và migration Flyway trên PostgreSQL nhúng. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageActionsAndReactionsApiTest {
    private static final EmbeddedPostgres PG;
    private static final Path MEDIA_DIR;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            MEDIA_DIR = Files.createTempDirectory("utechat-phase7-media-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Ghi đè cả datasource và Flyway để test chỉ kết nối database nhúng.
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("jwt.secret", () -> "phase7-fixture-key-never-used-for-deployment");
        registry.add("app.media.storage-dir", MEDIA_DIR::toString);
    }

    @AfterAll
    static void closeDatabase() throws IOException {
        PG.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private TestClock clock;

    private User alice;
    private User bob;
    private String conversation;

    @BeforeEach
    void createConversationWithTwoActiveMembers() throws Exception {
        clock.now = Instant.now();
        alice = createUser("Alice");
        bob = createUser("Bob");
        conversation = body(mvc.perform(post("/api/conversations/direct")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", bob.id().toString()))))
                .andExpect(status().isOk())).path("id").asText();
    }

    @Test
    void recallUsesServerClockAndSanitizesRestAndSyncHistory() throws Exception {
        String marker = "nội dung phải biến mất sau khi gỡ";
        long id = send(alice, "TEXT", Map.of("text", marker));
        long expired = send(alice, "TEXT", Map.of("text", "Hết hạn"));
        // Điều chỉnh mốc lưu của tin để kiểm hai phía biên mà không ngủ.
        setCreatedAt(id, clock.instant().minusSeconds(86_399));
        setCreatedAt(expired, clock.instant().minusSeconds(86_401));
        react(bob, id, "👍").andExpect(status().isOk());

        recall(bob, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        recall(alice, expired).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RECALL_EXPIRED"));
        recall(alice, id).andExpect(status().isOk()).andExpect(jsonPath("$.recalledAt").isNotEmpty())
                .andExpect(jsonPath("$.text").doesNotExist());
        recall(alice, id).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM public.message_reactions WHERE message_id = ?", Integer.class, id));
        assertEquals(2, eventCount("MESSAGE_RECALLED", id));
        react(bob, id, "👍").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        JsonNode history = history(bob);
        JsonNode recalled = history.get(1);
        assertEquals(id, recalled.path("id").asLong());
        assertTrue(recalled.hasNonNull("recalledAt"));
        assertFalse(recalled.hasNonNull("text"));
        JsonNode sync = body(mvc.perform(get("/api/sync").header("Authorization", bob.authorization()).param("after", "0"))
                .andExpect(status().isOk()));
        assertFalse(sync.toString().contains(marker));
        assertTrue(sync.path("events").toString().contains("MESSAGE_RECALLED"));
    }

    @Test
    void privateDeleteHidesMediaPreviewAndReplayForTheActorOnly() throws Exception {
        long previous = send(alice, "TEXT", Map.of("text", "Tin trước"));
        String media = upload(alice);
        long id = send(alice, "IMAGE", Map.of("mediaUrl", media, "text", "caption riêng"));
        // Xóa riêng lặp lại và sau khi chặn vẫn trả 204.
        jdbc.update("""
                INSERT INTO public.contacts(owner_id, contact_user_id, blocked, is_friend)
                VALUES (?, ?, TRUE, FALSE)
                """, alice.id(), bob.id());
        deleteMessage(bob, id).andExpect(status().isNoContent());
        deleteMessage(bob, id).andExpect(status().isNoContent());
        assertEquals(1, eventCount("MESSAGE_DELETED_FOR_ME", id));
        assertEquals(bob.id(), jdbc.queryForObject("""
                SELECT user_id FROM public.sync_events WHERE event_type='MESSAGE_DELETED_FOR_ME' AND entity_id=?
                """, UUID.class, Long.toString(id)));
        assertEquals(1, history(bob).size());
        assertEquals(2, history(alice).size());
        mvc.perform(get(media).header("Authorization", bob.authorization())).andExpect(status().isForbidden());
        mvc.perform(get(media).header("Authorization", alice.authorization())).andExpect(status().isOk());
        mvc.perform(get("/api/conversations/" + conversation).header("Authorization", bob.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastMessageId").value(previous))
                .andExpect(jsonPath("$.unreadCount").value(1));
        JsonNode sync = body(mvc.perform(get("/api/sync").header("Authorization", bob.authorization()).param("after", "0"))
                .andExpect(status().isOk()));
        assertFalse(sync.toString().contains("caption riêng"));
        assertTrue(sync.path("events").toString().contains("MESSAGE_DELETED_FOR_ME"));
    }

    @Test
    void recallRevokesAttachmentForBothUsersAndCannotReattachIt() throws Exception {
        String media = upload(alice);
        long id = send(alice, "IMAGE", Map.of("mediaUrl", media));
        UUID attachment = UUID.fromString(media.substring(media.lastIndexOf('/') + 1));
        Path path = MEDIA_DIR.resolve(jdbc.queryForObject(
                "SELECT storage_path FROM public.attachments WHERE id = ?", String.class, attachment));
        assertTrue(Files.exists(path));
        recall(alice, id).andExpect(status().isOk()).andExpect(jsonPath("$.mediaUrl").doesNotExist());
        for (User user : List.of(alice, bob)) {
            mvc.perform(get(media).header("Authorization", user.authorization())).andExpect(status().isNotFound());
        }
        assertFalse(Files.exists(path));
        sendRequest(alice, "IMAGE", Map.of("mediaUrl", media)).andExpect(status().isNotFound());
    }

    @Test
    void reactionCanChangeAndRemoveAndRejectsBlockedOrUnsupportedRequests() throws Exception {
        long id = send(alice, "TEXT", Map.of("text", "Phản hồi"));
        react(bob, id, "❤").andExpect(status().isOk()).andExpect(jsonPath("$.emoji").value("❤️"));
        for (String emoji : List.of("😂", "👍", "😮", "😢", "😡")) {
            react(bob, id, emoji).andExpect(status().isOk()).andExpect(jsonPath("$.reactions.length()").value(1))
                    .andExpect(jsonPath("$.reactions[0].emoji").value(emoji));
        }
        assertEquals("😡", history(alice).get(0).path("reactions").get(0).path("emoji").asText());
        react(bob, id, "🔥").andExpect(status().isBadRequest());
        react(bob, id, "😡").andExpect(status().isOk()).andExpect(jsonPath("$.action").value("REMOVED"))
                .andExpect(jsonPath("$.reactions.length()").value(0));
        react(bob, id, null).andExpect(status().isOk()).andExpect(jsonPath("$.action").value("REMOVED"));
        assertEquals(14, eventCount("MESSAGE_REACTION_UPDATED", id));

        jdbc.update("INSERT INTO public.contacts(owner_id, contact_user_id, blocked) VALUES (?, ?, TRUE)", alice.id(), bob.id());
        react(bob, id, "👍").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BLOCKED"));
        recall(alice, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BLOCKED"));
    }

    @Test
    void groupActionsRespectAdminSenderRoleHistoryCutoffAndReadOnlyMembership() throws Exception {
        // Tạo nhóm có ADMIN và thành viên để kiểm quyền trên tin do thành viên gửi.
        UUID group = jdbc.queryForObject(
                "INSERT INTO public.conversations(type,title) VALUES ('GROUP','Nhóm kiểm thử') RETURNING id", UUID.class);
        jdbc.update("""
                INSERT INTO public.conversation_members(conversation_id,user_id,role)
                VALUES (?, ?, 'ADMIN'), (?, ?, 'MEMBER')
                """, group, alice.id(), group, bob.id());
        conversation = group.toString();
        long id = send(bob, "TEXT", Map.of("text", "Chỉ tác giả được gỡ", "expectedVisibleFromMessageId", 0));
        recall(alice, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        long systemMessage = insertMessage(group, bob, "SYSTEM");
        long callMessage = insertMessage(group, bob, "CALL");
        recall(bob, systemMessage).andExpect(status().isConflict());
        recall(bob, callMessage).andExpect(status().isConflict());
        react(alice, systemMessage, "👍").andExpect(status().isConflict());
        setCreatedAt(id, clock.instant().minusSeconds(5));
        jdbc.update("UPDATE public.conversation_members SET left_at = now() WHERE conversation_id = ? AND user_id = ?",
                group, bob.id());
        recall(bob, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        react(bob, id, "👍").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        deleteMessage(bob, id).andExpect(status().isNoContent());
        long later = insertMessage(group, alice, "TEXT");
        setCreatedAt(later, clock.instant().plusSeconds(60));
        // Thành viên đã rời nhóm không được tìm ra tin phát sinh sau lúc rời qua API xóa riêng.
        deleteMessage(bob, later).andExpect(status().isNotFound());
        jdbc.update("""
                UPDATE public.conversation_members SET left_at = NULL, visible_from_message_id = ?
                WHERE conversation_id = ? AND user_id = ?
                """, systemMessage, group, bob.id());
        deleteMessage(bob, id).andExpect(status().isNotFound());
    }

    private long insertMessage(UUID group, User from, String type) {
        return jdbc.queryForObject("""
                INSERT INTO public.messages(conversation_id,sender_id,client_id,type)
                VALUES (?, ?, gen_random_uuid(), ?) RETURNING id
                """, Long.class, group, from.id(), type);
    }

    private void setCreatedAt(long id, Instant time) {
        jdbc.update("UPDATE public.messages SET created_at = ? WHERE id = ?", Timestamp.from(time), id);
    }

    private int eventCount(String type, long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.sync_events WHERE event_type = ? AND entity_id = ?",
                Integer.class, type, Long.toString(id));
    }

    private String upload(User from) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        return body(mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png.toByteArray()))
                        .param("kind", "IMAGE").param("clientId", UUID.randomUUID().toString())
                        .header("Authorization", from.authorization())).andExpect(status().isCreated()))
                .path("mediaUrl").asText();
    }

    private long send(User user, String type, Map<String, Object> data) throws Exception {
        return body(sendRequest(user, type, data).andExpect(status().isCreated())).path("id").asLong();
    }

    private ResultActions sendRequest(User user, String type, Map<String, Object> data) throws Exception {
        var payload = json.valueToTree(data).deepCopy();
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) payload;
        request.put("clientId", UUID.randomUUID().toString()).put("type", type);
        return mvc.perform(post(path())
                .header("Authorization", user.authorization())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private ResultActions recall(User user, long id) throws Exception {
        return mvc.perform(post(path() + "/" + id + "/recall").header("Authorization", user.authorization()));
    }

    private ResultActions deleteMessage(User user, long id) throws Exception {
        return mvc.perform(delete(path() + "/" + id).header("Authorization", user.authorization()));
    }

    private ResultActions react(User user, long id, String emoji) throws Exception {
        return mvc.perform(put(path() + "/" + id + "/reaction").header("Authorization", user.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("emoji", emoji))));
    }

    private JsonNode history(User user) throws Exception {
        return body(mvc.perform(get(path()).header("Authorization", user.authorization())).andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private String path() {
        return "/api/conversations/" + conversation + "/messages";
    }

    private User createUser(String name) {
        UUID id = UUID.randomUUID();
        String device = id.toString().replace("-", "").repeat(2);
        jdbc.update("INSERT INTO public.users(id,display_name,password_hash) VALUES (?, ?, 'fixture')", id, name);
        jdbc.update("""
                INSERT INTO public.user_devices(user_id,device_id_hash,device_name,trusted,token_valid_after)
                VALUES (?, ?, 'TestPhone', TRUE, ?)
                """, id, device, Timestamp.from(clock.instant().minusSeconds(10)));
        return new User(id, "Bearer " + jwt.issueAccessToken(id, device));
    }

    private record User(UUID id, String authorization) {
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        TestClock actionClock() {
            return new TestClock();
        }
    }

    /** Đồng hồ test giữ thời gian cố định trong mỗi test. */
    static class TestClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
