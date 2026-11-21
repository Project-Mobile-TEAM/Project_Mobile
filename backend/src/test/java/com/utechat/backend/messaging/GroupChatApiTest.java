package com.utechat.backend.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.service.AccountService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * Bộ kiểm thử tích hợp trên EmbeddedPostgres (không cần Docker) cho Plan 05-02:
 * tạo nhóm 2..50 bạn bè ACCEPTED, phân quyền ADMIN, muted không sinh SYSTEM,
 * cô lập lịch sử chặt visible_from_message_id (>), bàn giao ADMIN 3 nhánh,
 * tuần tự hóa khóa nhóm khi send/remove/re-add + 409 MEMBERSHIP_CHANGED,
 * biên nhận đơn điệu DELIVERED >= READ và tự phong ADMIN khi xóa tài khoản sau 15 ngày.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupChatApiTest {
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
        r.add("jwt.secret", () -> "utechat-phase5-group-test-secret-0123456789abcdef");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private AccountService accountService;

    private TestUser alice;
    private TestUser bob;
    private TestUser charlie;

    @BeforeEach
    void setUp() {
        alice = createActiveUser("Alice");
        bob = createActiveUser("Bob");
        charlie = createActiveUser("Charlie");
        makeAcceptedFriends(alice.id, bob.id);
        makeAcceptedFriends(alice.id, charlie.id);
    }

    @Test
    void createGroupRequiresTwoAcceptedFriendsAndEnforcesMax50Members() throws Exception {
        // Ít hơn 2 thành viên khác -> 400 VALIDATION.
        mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Nhom thieu nguoi",
                                "memberIds", List.of(bob.id.toString())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION"));

        // Thêm người lạ chưa kết bạn -> 403 CONTACT_REQUIRED.
        TestUser stranger = createActiveUser("Stranger");
        mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Nhom co nguoi la",
                                "memberIds", List.of(bob.id.toString(), stranger.id.toString())))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CONTACT_REQUIRED"));

        // Thêm người bị chặn -> 403 BLOCKED ưu tiên trước CONTACT_REQUIRED.
        TestUser blockedFriend = createActiveUser("Blocked");
        makeAcceptedFriends(alice.id, blockedFriend.id);
        jdbc.update("UPDATE contacts SET blocked = TRUE WHERE owner_id = ? AND contact_user_id = ?",
                blockedFriend.id, alice.id);
        mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Nhom bi chan",
                                "memberIds", List.of(bob.id.toString(), blockedFriend.id.toString())))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BLOCKED"));

        // Tạo nhóm hợp lệ 3 người -> 201 Created + có tin SYSTEM GROUP_CREATED.
        String groupId = createGroup(alice, "Nhom UTE", List.of(bob.id, charlie.id));
        Integer sysCount = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM' AND system_event = 'GROUP_CREATED'",
                Integer.class, UUID.fromString(groupId));
        assertEquals(1, sysCount);

        // Thử tạo nhóm 51 người (50 người khác + người tạo) -> 409 GROUP_FULL.
        List<String> fiftyOthers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            TestUser u = createActiveUser("Friend" + i);
            makeAcceptedFriends(alice.id, u.id);
            fiftyOthers.add(u.id.toString());
        }
        mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Nhom qua 50",
                                "memberIds", fiftyOthers))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GROUP_FULL"));

        // Mở rộng nhóm đang có 3 người lên 50 (thêm 47 người) thành công, thêm người thứ 51 -> 409 GROUP_FULL.
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", fiftyOthers.subList(0, 47)))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(fiftyOthers.get(47))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GROUP_FULL"));
    }

    @Test
    void nonAdminCannotRenameAddRemoveOrChangeRoles() throws Exception {
        String groupId = createGroup(alice, "Nhom Quy Tac", List.of(bob.id, charlie.id));
        TestUser dave = createActiveUser("Dave");
        makeAcceptedFriends(bob.id, dave.id);

        // Bob là MEMBER không được đổi tên, thêm người, xóa người khác hay đổi vai trò.
        mvc.perform(patch("/api/conversations/" + groupId)
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "Ten moi"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(dave.id.toString())))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + charlie.id)
                        .header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        mvc.perform(put("/api/conversations/" + groupId + "/members/" + charlie.id + "/role")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        // MEMBER bật muted cá nhân -> 200 OK và tuyệt đối không tạo thêm tin SYSTEM hay sync_events.
        int sysBefore = countSystemMessages(groupId);
        int eventsBefore = countAllSyncEvents();

        mvc.perform(patch("/api/conversations/" + groupId)
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("muted", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted").value(true));

        assertEquals(sysBefore, countSystemMessages(groupId));
        assertEquals(eventsBefore, countAllSyncEvents());
    }

    @Test
    void addReaddEnforcesStrictGreaterVisibleFromMessageIdAndIdempotentSystem() throws Exception {
        String groupId = createGroup(alice, "Nhom Lich Su", List.of(bob.id, charlie.id));
        long m1 = sendGroupText(alice, groupId, "Tin 1", 0L);
        long m2 = sendGroupText(alice, groupId, "Tin 2", 0L);

        // Xóa Charlie khỏi nhóm rồi Alice gửi thêm m3.
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + charlie.id)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isNoContent());
        long m3 = sendGroupText(alice, groupId, "Tin bi mat 3", 0L);

        // Thêm lại Charlie -> mốc visible_from_message_id phải đúng bằng m3.id (lấy trước khi chèn MEMBER_ADDED).
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(charlie.id.toString())))))
                .andExpect(status().isNoContent());

        Long visibleFrom = jdbc.queryForObject(
                "SELECT visible_from_message_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Long.class, UUID.fromString(groupId), charlie.id);
        assertEquals(m3, visibleFrom);

        // Charlie tải lịch sử -> chỉ thấy MEMBER_ADDED (id > m3), tuyệt đối không thấy m1, m2 hay m3.
        MvcResult hist = mvc.perform(get("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = json.readTree(hist.getResponse().getContentAsString());
        assertEquals(1, arr.size());
        assertTrue(arr.get(0).get("id").asLong() > m3);
        assertEquals("SYSTEM", arr.get(0).get("type").asText());
        assertEquals("MEMBER_ADDED", arr.get(0).get("systemEvent").asText());
        assertTrue(m1 < m2 && m2 < m3);

        // Gọi POST /members lặp lại cho Charlie đang ở trong nhóm -> 204 no-op, không thêm SYSTEM hay sync_events.
        int sysBefore = countSystemMessages(groupId);
        int eventsBefore = countAllSyncEvents();
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(charlie.id.toString())))))
                .andExpect(status().isNoContent());
        assertEquals(sysBefore, countSystemMessages(groupId));
        assertEquals(eventsBefore, countAllSyncEvents());
    }

    @Test
    void leaveGroupCoversAllThreeAdminCasesAndBlocksRemovedUserAfterFinalEvent() throws Exception {
        String groupId = createGroup(alice, "Nhom Ban Giao", List.of(bob.id, charlie.id));

        // Nhánh A: Alice là ADMIN duy nhất còn thành viên khác -> thiếu successorUserId trả 409 ADMIN_HANDOVER_REQUIRED.
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + alice.id)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_HANDOVER_REQUIRED"));

        // Truyền successorUserId = Bob -> bàn giao ADMIN cho Bob và Alice rời trong cùng transaction.
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + alice.id)
                        .param("successorUserId", bob.id.toString())
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isNoContent());

        String bobRole = jdbc.queryForObject(
                "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                String.class, UUID.fromString(groupId), bob.id);
        assertEquals("ADMIN", bobRole);

        // Alice nhận đúng 1 sự kiện CONVERSATION_UPDATED cuối cùng có leftAt != null.
        List<String> aliceConvPayloads = jdbc.query(
                "SELECT payload_json::text FROM sync_events WHERE user_id = ? AND event_type = 'CONVERSATION_UPDATED' AND entity_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1),
                alice.id, groupId);
        assertFalse(aliceConvPayloads.isEmpty());
        assertFalse(json.readTree(aliceConvPayloads.get(0)).path("leftAt").isMissingNode());
        assertFalse(json.readTree(aliceConvPayloads.get(0)).path("leftAt").isNull());

        // Sau khi rời: GET/POST messages và POST read của Alice đều bị 403 NOT_A_MEMBER.
        mvc.perform(get("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));

        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", "Tin sau khi roi",
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));

        mvc.perform(post("/api/conversations/" + groupId + "/read")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("upToMessageId", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));

        // Tin nhắn mới của Bob không được đẩy MESSAGE_CREATED sang Alice nữa.
        long postLeaveMsg = sendGroupText(bob, groupId, "Tin rieng sau khi Alice roi", 0L);
        Integer alicePostEvents = jdbc.queryForObject(
                "SELECT count(*) FROM sync_events WHERE user_id = ? AND event_type = 'MESSAGE_CREATED' AND entity_id = ?",
                Integer.class, alice.id, String.valueOf(postLeaveMsg));
        assertEquals(0, alicePostEvents);

        // Nhánh B: phong thêm Charlie làm ADMIN -> Bob rời trực tiếp không cần successorUserId.
        mvc.perform(put("/api/conversations/" + groupId + "/members/" + charlie.id + "/role")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + bob.id)
                        .header("Authorization", "Bearer " + bob.token))
                .andExpect(status().isNoContent());

        // Nhánh C: Charlie là thành viên cuối cùng -> rời trực tiếp không bắt chọn kế nhiệm.
        mvc.perform(delete("/api/conversations/" + groupId + "/members/" + charlie.id)
                        .header("Authorization", "Bearer " + charlie.token))
                .andExpect(status().isNoContent());
    }

    @Test
    void groupSendAndRemoveReaddSerializeUnderOneConversationLock() throws Exception {
        String groupId = createGroup(alice, "Nhom Khoa Dong Thoi", List.of(bob.id, charlie.id));
        UUID groupUuid = UUID.fromString(groupId);

        // Thứ tự 1: một giao dịch giữ khóa FOR UPDATE hội thoại và xóa Charlie; đồng thời Charlie gửi tin bị chặn đợi khóa rồi trả 403 NOT_A_MEMBER.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch sendStarted = new CountDownLatch(1);
        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement ps = locker.prepareStatement("SELECT id FROM conversations WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, groupUuid);
                ps.executeQuery();
            }
            lockAcquired.countDown();

            String blockedClientId = UUID.randomUUID().toString();
            Future<MvcResult> sendFuture = pool.submit(() -> {
                lockAcquired.await();
                sendStarted.countDown();
                return mvc.perform(post("/api/conversations/" + groupId + "/messages")
                                .header("Authorization", "Bearer " + charlie.token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "clientId", blockedClientId,
                                        "type", "TEXT",
                                        "text", "Tin bi xoa truoc khi gui",
                                        "expectedVisibleFromMessageId", 0))))
                        .andReturn();
            });

            sendStarted.await();
            Thread.sleep(150);
            try (PreparedStatement upd = locker.prepareStatement(
                    "UPDATE conversation_members SET left_at = now() WHERE conversation_id = ? AND user_id = ?")) {
                upd.setObject(1, groupUuid);
                upd.setObject(2, charlie.id);
                upd.executeUpdate();
            }
            locker.commit();

            MvcResult res = sendFuture.get();
            assertEquals(403, res.getResponse().getStatus());
            assertEquals("NOT_A_MEMBER", json.readTree(res.getResponse().getContentAsString()).path("error").path("code").asText());
            Integer insertedCount = jdbc.queryForObject(
                    "SELECT count(*) FROM messages WHERE sender_id = ? AND client_id = ?",
                    Integer.class, charlie.id, UUID.fromString(blockedClientId));
            assertEquals(0, insertedCount);
        } finally {
            pool.shutdownNow();
        }

        // Thêm lại Charlie sau khi có tin mới -> mốc visible_from_message_id mới > 0.
        long beforeReadd = sendGroupText(alice, groupId, "Tin trong luc Charlie vang", 0L);
        mvc.perform(post("/api/conversations/" + groupId + "/members")
                        .header("Authorization", "Bearer " + alice.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userIds", List.of(charlie.id.toString())))))
                .andExpect(status().isNoContent());

        // Gửi tin offline cũ mang mốc cũ expectedVisibleFromMessageId = 0 -> 409 MEMBERSHIP_CHANGED và không ghi tin/event.
        String staleClientId = UUID.randomUUID().toString();
        mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + charlie.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", staleClientId,
                                "type", "TEXT",
                                "text", "Tin tu lan tham gia cu",
                                "expectedVisibleFromMessageId", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_CHANGED"));

        Integer staleInserted = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE sender_id = ? AND client_id = ?",
                Integer.class, charlie.id, UUID.fromString(staleClientId));
        assertEquals(0, staleInserted);

        // Gửi tin mới với đúng mốc hiện tại (beforeReadd) -> thành công 201 Created.
        long newMsgId = sendGroupText(charlie, groupId, "Tin hop le sau khi vao lai", beforeReadd);
        assertTrue(newMsgId > beforeReadd);
    }

    @Test
    void watermarksAreMonotonicAndReadAdvancesDelivered() throws Exception {
        String groupId = createGroup(alice, "Nhom Bien Nhan", List.of(bob.id, charlie.id));
        long m1 = sendGroupText(alice, groupId, "M1", 0L);
        long m2 = sendGroupText(alice, groupId, "M2", 0L);
        long m3 = sendGroupText(alice, groupId, "M3", 0L);

        // Bob gọi thẳng /read tại m3 (chưa gọi /delivered) -> cả last_read và last_delivered đều nâng lên m3.
        mvc.perform(post("/api/conversations/" + groupId + "/read")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("upToMessageId", m3))))
                .andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_delivered_message_id, last_read_message_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                UUID.fromString(groupId), bob.id);
        assertEquals(m3, ((Number) row.get("last_delivered_message_id")).longValue());
        assertEquals(m3, ((Number) row.get("last_read_message_id")).longValue());

        // Gọi lại /delivered và /read với mốc nhỏ hơn m1 < m3 -> không lùi mốc và không ghi thêm RECEIPT_UPDATED.
        int receiptEventsBefore = countReceiptEvents(groupId);
        mvc.perform(post("/api/conversations/" + groupId + "/delivered")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("upToMessageId", m1))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/conversations/" + groupId + "/read")
                        .header("Authorization", "Bearer " + bob.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("upToMessageId", m2))))
                .andExpect(status().isNoContent());

        Map<String, Object> afterRow = jdbc.queryForMap(
                "SELECT last_delivered_message_id, last_read_message_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                UUID.fromString(groupId), bob.id);
        assertEquals(m3, ((Number) afterRow.get("last_delivered_message_id")).longValue());
        assertEquals(m3, ((Number) afterRow.get("last_read_message_id")).longValue());
        assertEquals(receiptEventsBefore, countReceiptEvents(groupId));
    }

    @Test
    void accountDeletionPurgeAutoPromotesSoleAdmin() throws Exception {
        String groupId = createGroup(alice, "Nhom Tu Phong Admin", List.of(bob.id, charlie.id));
        UUID groupUuid = UUID.fromString(groupId);

        // Ép joined_at của Bob sớm hơn Charlie để khẳng định Bob là người được tự động phong ADMIN.
        jdbc.update("UPDATE conversation_members SET joined_at = now() - interval '2 hours' WHERE conversation_id = ? AND user_id = ?",
                groupUuid, bob.id);
        jdbc.update("UPDATE conversation_members SET joined_at = now() - interval '1 hour' WHERE conversation_id = ? AND user_id = ?",
                groupUuid, charlie.id);

        // Giả lập tài khoản Alice (ADMIN duy nhất) đã quá hạn 15 ngày chờ xóa và chạy job dọn.
        jdbc.update("UPDATE users SET deletion_scheduled_at = now() - interval '1 minute' WHERE id = ?", alice.id);
        accountService.purgeExpiredDeletions();

        String bobRole = jdbc.queryForObject(
                "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                String.class, groupUuid, bob.id);
        String aliceStatus = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, alice.id);
        Timestamp aliceLeftAt = jdbc.queryForObject(
                "SELECT left_at FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Timestamp.class, groupUuid, alice.id);

        assertEquals("ADMIN", bobRole);
        assertEquals("DELETED", aliceStatus);
        assertNotNull(aliceLeftAt);
    }

    private String createGroup(TestUser creator, String title, List<UUID> members) throws Exception {
        MvcResult res = mvc.perform(post("/api/conversations/group")
                        .header("Authorization", "Bearer " + creator.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", title,
                                "memberIds", members.stream().map(UUID::toString).toList()))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private long sendGroupText(TestUser sender, String groupId, String text, long expectedVisibleFrom) throws Exception {
        MvcResult res = mvc.perform(post("/api/conversations/" + groupId + "/messages")
                        .header("Authorization", "Bearer " + sender.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "clientId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "text", text,
                                "expectedVisibleFromMessageId", expectedVisibleFrom))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private int countSystemMessages(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM'",
                Integer.class, UUID.fromString(groupId));
        return n == null ? 0 : n;
    }

    private int countAllSyncEvents() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sync_events", Integer.class);
        return n == null ? 0 : n;
    }

    private int countReceiptEvents(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM sync_events WHERE event_type = 'RECEIPT_UPDATED' AND entity_id LIKE ?",
                Integer.class, groupId + ":%");
        return n == null ? 0 : n;
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
                        + "VALUES (?, ?, 'GroupPhone', TRUE, ?)",
                userId, deviceHash, Timestamp.from(now.minusSeconds(5)));
        return new TestUser(userId, jwt.issueAccessToken(userId, deviceHash));
    }

    private static String randomDigits(int len) {
        String s = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        while (s.length() < len) s = s + "5";
        return s.substring(0, len);
    }

    private record TestUser(UUID id, String token) {
    }
}
