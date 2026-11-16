package com.utechat.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.service.OtpService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử đầu-cuối của xác thực và tài khoản: HTTP + Spring Security + JWT + SQL chạy trên PostgreSQL thật (nhúng)
 * với đúng schema V1. Mỗi test dùng số điện thoại riêng nên chạy độc lập trên cùng một CSDL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {
    private static final EmbeddedPostgres PG;
    private static final Path OTP_DIR;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
            try (Connection c = PG.getPostgresDatabase().getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource("src/main/resources/db/migration/V1__initial_schema.sql"));
                ScriptUtils.executeSqlScript(c, new FileSystemResource("src/main/resources/db/migration/V3__account_deletion_grace.sql"));
            }
            OTP_DIR = Files.createTempDirectory("otp-local-test");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("utechat.otp.local-dir", OTP_DIR::toString);
        r.add("app.otp.cooldown-seconds", () -> "0"); // Test gửi nhiều OTP liên tiếp cho cùng một số.
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    // ---------- tiện ích ----------
    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body,
            String token, int expected) throws Exception {
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        String text = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }
    private JsonNode doPost(String path, Object body, String token, int expected) throws Exception { return call(post(path), body, token, expected); }
    private JsonNode doGet(String path, String token, int expected) throws Exception { return call(get(path), null, token, expected); }

    /** Đăng ký đủ 3 bước và trả JWT của thiết bị. */
    private String register(String phone, String deviceId, String password) throws Exception {
        JsonNode ch = doPost("/api/auth/register/otp", Map.of("phone", phone, "deviceId", deviceId), null, 200);
        String reg = doPost("/api/auth/register/verify", Map.of("challengeId", ch.get("challengeId").asText(),
                "code", ch.get("demoOtp").asText()), null, 200).get("registrationToken").asText();
        return doPost("/api/auth/register/complete", Map.of("registrationToken", reg, "displayName", "Người Dùng",
                "password", password, "deviceName", "Pixel " + deviceId), null, 201).get("token").asText();
    }

    private String login(String phone, String password, String deviceId) throws Exception {
        return doPost("/api/auth/login/password", Map.of("phone", phone, "password", password, "deviceId", deviceId,
                "deviceName", "Máy " + deviceId), null, 200).get("token").asText();
    }

    private static String code(JsonNode body) { return body.get("error").get("code").asText(); }

    // ---------- đăng ký ----------
    @Test void registerFlowCreatesAccountAndMeWorks() throws Exception {
        String token = register("+84911000001", "device-0001", "abcd1234");
        JsonNode me = doGet("/api/me", token, 200);
        assertEquals("+84911000001", me.get("phone").asText());
        assertEquals("Người Dùng", me.get("displayName").asText());
        assertTrue(me.get("allowMessagesFromNonContacts").asBoolean()); // Mặc định theo F-01.
        // Số đã đăng ký thì xin OTP đăng ký lại bị 409.
        assertEquals("PHONE_TAKEN", code(doPost("/api/auth/register/otp", Map.of("phone", "+84911000001", "deviceId", "device-0001"), null, 409)));
    }

    @Test void otpIsWrittenToLocalFolderAndCannotBeReplayed() throws Exception {
        JsonNode ch = doPost("/api/auth/register/otp", Map.of("phone", "+84911000002", "deviceId", "device-0002"), null, 200);
        String code = ch.get("demoOtp").asText();
        assertTrue(Files.readString(OTP_DIR.resolve("84911000002.txt")).contains("code=" + code));
        Map<String, String> verify = Map.of("challengeId", ch.get("challengeId").asText(), "code", code);
        doPost("/api/auth/register/verify", verify, null, 200);
        assertEquals("OTP_INVALID", code(doPost("/api/auth/register/verify", verify, null, 400))); // Dùng lại bị từ chối.
    }

    @Test void wrongOtpFiveTimesLocksTheChallenge() throws Exception {
        JsonNode ch = doPost("/api/auth/register/otp", Map.of("phone", "+84911000003", "deviceId", "device-0003"), null, 200);
        String wrong = ch.get("demoOtp").asText().equals("000000") ? "111111" : "000000";
        Map<String, String> bad = Map.of("challengeId", ch.get("challengeId").asText(), "code", wrong);
        for (int i = 0; i < 4; i++) assertEquals("OTP_INVALID", code(doPost("/api/auth/register/verify", bad, null, 400)));
        assertEquals("OTP_ATTEMPTS", code(doPost("/api/auth/register/verify", bad, null, 429)));
        // Sau khi hết lượt, ngay cả mã đúng cũng không còn dùng được.
        assertEquals("OTP_ATTEMPTS", code(doPost("/api/auth/register/verify",
                Map.of("challengeId", ch.get("challengeId").asText(), "code", ch.get("demoOtp").asText()), null, 429)));
    }

    @Test void registrationTokenIsSingleUseAndNotAnAccessToken() throws Exception {
        JsonNode ch = doPost("/api/auth/register/otp", Map.of("phone", "+84911000004", "deviceId", "device-0004"), null, 200);
        String reg = doPost("/api/auth/register/verify", Map.of("challengeId", ch.get("challengeId").asText(),
                "code", ch.get("demoOtp").asText()), null, 200).get("registrationToken").asText();
        doGet("/api/me", reg, 401); // Token tạm không được dùng làm token truy cập.
        Map<String, String> complete = Map.of("registrationToken", reg, "displayName", "A", "password", "abcd1234", "deviceName", "X");
        doPost("/api/auth/register/complete", complete, null, 201);
        assertEquals("OTP_EXPIRED", code(doPost("/api/auth/register/complete", complete, null, 400))); // Replay bị từ chối.
    }

    @Test void weakPasswordIsRejectedWithoutConsumingTheToken() throws Exception {
        JsonNode ch = doPost("/api/auth/register/otp", Map.of("phone", "+84911000005", "deviceId", "device-0005"), null, 200);
        String reg = doPost("/api/auth/register/verify", Map.of("challengeId", ch.get("challengeId").asText(),
                "code", ch.get("demoOtp").asText()), null, 200).get("registrationToken").asText();
        assertEquals("WEAK_PASSWORD", code(doPost("/api/auth/register/complete",
                Map.of("registrationToken", reg, "displayName", "A", "password", "short", "deviceName", "X"), null, 400)));
        // Token chưa bị tiêu thụ nên nhập lại mật khẩu đủ mạnh vẫn thành công.
        doPost("/api/auth/register/complete", Map.of("registrationToken", reg, "displayName", "A", "password", "abcd1234", "deviceName", "X"), null, 201);
    }

    // ---------- đăng nhập, khoá, đăng xuất ----------
    @Test void fiveWrongPasswordsLockTheDevice() throws Exception {
        register("+84911000006", "device-0006", "abcd1234");
        Map<String, String> bad = Map.of("phone", "+84911000006", "password", "sai-mat-khau", "deviceId", "device-0006", "deviceName", "X");
        for (int i = 0; i < 5; i++) assertEquals("BAD_CREDENTIALS", code(doPost("/api/auth/login/password", bad, null, 401)));
        JsonNode locked = doPost("/api/auth/login/password", bad, null, 423);
        assertEquals("LOCKED", code(locked));
        assertTrue(locked.get("error").get("details").get("retryAfterSeconds").asLong() > 0);
        // Đang khoá thì mật khẩu đúng cũng bị từ chối; thiết bị khác không bị kế thừa khoá riêng của thiết bị này.
        doPost("/api/auth/login/password", Map.of("phone", "+84911000006", "password", "abcd1234", "deviceId", "device-0006", "deviceName", "X"), null, 423);
        login("+84911000006", "abcd1234", "device-other-06");
    }

    @Test void unknownPhoneGivesTheSameErrorAsWrongPassword() throws Exception {
        assertEquals("BAD_CREDENTIALS", code(doPost("/api/auth/login/password",
                Map.of("phone", "+84911999999", "password", "abcd1234", "deviceId", "device-0007", "deviceName", "X"), null, 401)));
    }

    @Test void logoutInvalidatesOnlyThatDevice() throws Exception {
        String a = register("+84911000008", "device-0008", "abcd1234");
        String b = login("+84911000008", "abcd1234", "device-0088");
        doGet("/api/me", a, 200);
        doPost("/api/auth/logout", null, a, 204);
        doGet("/api/me", a, 401);
        doGet("/api/me", b, 200);
        doGet("/api/me", null, 401);
    }

    @Test void loginByOtpAndUnknownPhoneDoesNotLeak() throws Exception {
        register("+84911000009", "device-0009", "abcd1234");
        JsonNode ch = doPost("/api/auth/login/otp/request", Map.of("phone", "+84911000009", "deviceId", "device-0099"), null, 200);
        String token = doPost("/api/auth/login/otp/verify", Map.of("challengeId", ch.get("challengeId").asText(),
                "code", ch.get("demoOtp").asText(), "deviceName", "Máy mới"), null, 200).get("token").asText();
        doGet("/api/me", token, 200);
        // Số chưa đăng ký vẫn nhận 200 và challengeId nhưng không có OTP, và không thể xác minh.
        JsonNode fake = doPost("/api/auth/login/otp/request", Map.of("phone", "+84911888888", "deviceId", "device-0099"), null, 200);
        assertNotNull(fake.get("challengeId"));
        assertNull(fake.get("demoOtp"));
        assertEquals("OTP_INVALID", code(doPost("/api/auth/login/otp/verify",
                Map.of("challengeId", fake.get("challengeId").asText(), "code", "123456", "deviceName", "X"), null, 400)));
    }

    // ---------- quên mật khẩu ----------
    @Test void passwordResetRevokesOtherDevicesAndSignsInTheCurrentOne() throws Exception {
        String phone = "+84911000010";
        String a = register(phone, "device-0010", "abcd1234");
        JsonNode ch = doPost("/api/auth/password-reset/otp", Map.of("phone", phone, "deviceId", "device-0110"), null, 200);
        String reset = doPost("/api/auth/password-reset/verify", Map.of("challengeId", ch.get("challengeId").asText(),
                "code", ch.get("demoOtp").asText()), null, 200).get("resetToken").asText();
        JsonNode done = doPost("/api/auth/password-reset/complete",
                Map.of("resetToken", reset, "newPassword", "moimoi123", "deviceName", "Máy khôi phục"), null, 200);
        doGet("/api/me", a, 401); // Thiết bị cũ bị đăng xuất.
        doGet("/api/me", done.get("token").asText(), 200); // Thiết bị đang khôi phục được đăng nhập luôn.
        doPost("/api/auth/login/password", Map.of("phone", phone, "password", "abcd1234", "deviceId", "device-0010", "deviceName", "X"), null, 401);
        login(phone, "moimoi123", "device-0010");
        // Token đặt lại chỉ dùng được một lần.
        assertEquals("OTP_EXPIRED", code(doPost("/api/auth/password-reset/complete",
                Map.of("resetToken", reset, "newPassword", "khac12345", "deviceName", "X"), null, 400)));
    }

    // ---------- tài khoản ----------
    @Test void patchMeUpdatesNameAndPrivacyFlags() throws Exception {
        String t = register("+84911000011", "device-0011", "abcd1234");
        JsonNode me = call(patch("/api/me"), Map.of("displayName", "  Tên Mới  ", "discoverable", false, "allowMessagesFromNonContacts", false), t, 200);
        assertEquals("Tên Mới", me.get("displayName").asText());
        assertFalse(me.get("discoverable").asBoolean());
        assertFalse(me.get("allowMessagesFromNonContacts").asBoolean());
        call(patch("/api/me"), Map.of("displayName", ""), t, 400);
        call(patch("/api/me"), Map.of("avatarMediaUrl", "http://evil.example/x.png"), t, 400);
        assertEquals("Tên Mới", doGet("/api/me", t, 200).get("displayName").asText()); // Lần gửi lỗi không đổi gì.
    }

    @Test void changePasswordChecksOldOneAndSignsOutOtherDevices() throws Exception {
        String phone = "+84911000012";
        String a = register(phone, "device-0012", "abcd1234");
        String b = login(phone, "abcd1234", "device-0112");
        // Sai mật khẩu hiện tại: 400 (không phải 401, để app không tự đăng xuất).
        assertEquals("BAD_CREDENTIALS", code(doPost("/api/me/password", Map.of("oldPassword", "sai", "newPassword", "moimoi123"), a, 400)));
        assertEquals("WEAK_PASSWORD", code(doPost("/api/me/password", Map.of("oldPassword", "abcd1234", "newPassword", "short"), a, 400)));
        doPost("/api/me/password", Map.of("oldPassword", "abcd1234", "newPassword", "moimoi123"), a, 204);
        doGet("/api/me", a, 200); // Thiết bị đang đổi vẫn đăng nhập.
        doGet("/api/me", b, 401); // Thiết bị khác bị đăng xuất.
        login(phone, "moimoi123", "device-0112");
    }

    @Test void devicesListAndRevoke() throws Exception {
        String phone = "+84911000013";
        String a = register(phone, "device-0013", "abcd1234");
        String b = login(phone, "abcd1234", "device-0113");
        JsonNode list = doGet("/api/account/devices", a, 200);
        assertEquals(2, list.size());
        String idA = null, idB = null;
        for (JsonNode d : list) {
            if (d.get("current").asBoolean()) idA = d.get("id").asText(); else idB = d.get("id").asText();
        }
        assertNotNull(idA);
        assertNotNull(idB);
        call(delete("/api/account/devices/" + idA), null, a, 404); // Không tự thu hồi thiết bị đang dùng (dùng đăng xuất).
        call(delete("/api/account/devices/" + idB), null, a, 204);
        doGet("/api/me", b, 401);
        assertEquals(1, doGet("/api/account/devices", a, 200).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sync_events WHERE entity_id = ? AND event_type = 'DEVICE_REVOKED'", Integer.class, idB));
    }

    @Test void deleteAccountWaits15DaysAndCanBeCanceledByLoggingInAgain() throws Exception {
        String phone = "+84911000014";
        String t = register(phone, "device-0014", "abcd1234");
        assertEquals("BAD_CREDENTIALS", code(call(delete("/api/me"), Map.of("password", "sai"), t, 400)));
        call(delete("/api/me"), Map.of("password", "abcd1234"), t, 204);
        doGet("/api/me", t, 401); // Mọi thiết bị bị đăng xuất ngay.

        // Trong 15 ngày: vẫn đăng nhập được, hồ sơ báo ngày bị xoá; số điện thoại chưa được giải phóng.
        JsonNode again = doPost("/api/auth/login/password", Map.of("phone", phone, "password", "abcd1234",
                "deviceId", "device-0014", "deviceName", "X"), null, 200);
        String due = again.get("user").get("deletionScheduledAt").asText();
        long days = java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(due)).toDays();
        assertTrue(days >= 14 && days <= 15, due);
        assertEquals("PHONE_TAKEN", code(doPost("/api/auth/register/otp", Map.of("phone", phone, "deviceId", "device-0214"), null, 409)));
        String t2 = again.get("token").asText();
        doPost("/api/me/cancel-deletion", null, t2, 204);
        assertTrue(doGet("/api/me", t2, 200).get("deletionScheduledAt").isNull());

        // Yêu cầu xoá lại rồi giả lập đã qua 15 ngày: bị xoá vĩnh viễn, số được giải phóng.
        call(delete("/api/me"), Map.of("password", "abcd1234"), t2, 204);
        jdbc.update("UPDATE users SET deletion_scheduled_at = now() - interval '1 minute' WHERE phone_e164 = ?", phone);
        doPost("/api/auth/login/password", Map.of("phone", phone, "password", "abcd1234", "deviceId", "device-0014", "deviceName", "X"), null, 401);
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM users WHERE display_name = 'Tài khoản đã xóa' ORDER BY deleted_at DESC LIMIT 1", String.class));
        register(phone, "device-0114", "abcd1234"); // Số được giải phóng nên đăng ký lại được.
    }

    @Test void invalidPhoneIsRejected() throws Exception {
        assertEquals("PHONE_INVALID", code(doPost("/api/auth/register/otp", Map.of("phone", "0912345678", "deviceId", "device-0015"), null, 400)));
    }

    // ---------- giới hạn gửi OTP (đúng cấu hình sản xuất: 60 giây) ----------
    @Test void otpCooldownAndHourlyLimit() {
        OtpService strict = new OtpService(jdbc, (p, c) -> { }, false, 60);
        strict.issue("+84911000020", "REGISTER", "h");
        ApiException e = assertThrows(ApiException.class, () -> strict.issue("+84911000020", "REGISTER", "h"));
        assertEquals("OTP_RATE_LIMIT", e.getCode()); // Gửi lại trong 60 giây bị chặn.

        OtpService noCooldown = new OtpService(jdbc, (p, c) -> { }, false, 0);
        for (int i = 0; i < 5; i++) noCooldown.issue("+84911000021", "REGISTER", "h");
        assertEquals("OTP_RATE_LIMIT", assertThrows(ApiException.class, () -> noCooldown.issue("+84911000021", "REGISTER", "h")).getCode());
    }
}
