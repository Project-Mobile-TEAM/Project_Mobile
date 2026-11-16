package com.utechat.backend.service;

import com.utechat.backend.dto.AuthDtos.AuthSuccessResponse;
import com.utechat.backend.dto.AuthDtos.OtpChallengeResponse;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.util.HashUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng ký, đăng nhập (mật khẩu / OTP), quên mật khẩu và đăng xuất (F-01 đến F-06).
 * Các bước xác minh OTP và kiểm tra throttle KHÔNG nằm trong transaction để lần sai vẫn được ghi;
 * chỉ bước tạo tài khoản / đặt lại mật khẩu mới chạy trong transaction.
 */
@Service
public class AuthService {
    // Băm giả để tốn thời gian BCrypt như thật khi số không tồn tại, tránh lộ số có tài khoản qua thời gian phản hồi.
    private final String dummyHash;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final OtpService otp;
    private final ThrottleService throttle;
    private final AccountService account;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, OtpService otp,
            ThrottleService throttle, AccountService account) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.otp = otp;
        this.throttle = throttle;
        this.account = account;
        this.dummyHash = encoder.encode("not-a-real-password"); // Băm hợp lệ thật, tạo một lần lúc khởi động.
    }

    // ================= Đăng ký (F-01) =================

    public OtpChallengeResponse requestRegisterOtp(String phone, String deviceId) {
        phone = phone(phone);
        String deviceHash = deviceHash(deviceId);
        if (findActiveUserId(phone) != null) throw new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN", "Phone already registered");
        return challenge(otp.issue(phone, "REGISTER", deviceHash));
    }

    /** Xác minh OTP đăng ký, mở một phiên chờ hoàn tất và trả registrationToken (10 phút, dùng một lần). */
    public String verifyRegisterOtp(String challengeId, String code) {
        OtpService.Verified v = otp.verify(challengeId, code, "REGISTER");
        UUID sid = openSession(null, v.deviceHash(), "OTP");
        return jwt.issueTempToken(JwtService.REGISTRATION, sid, v.phone(), null, v.deviceHash());
    }

    @Transactional
    public AuthSuccessResponse completeRegistration(String registrationToken, String displayName, String password, String deviceName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 50) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "displayName must be 1 to 50 characters");
        AccountService.requireStrong(password);
        Claims claims = parseTemp(registrationToken, JwtService.REGISTRATION);
        consumeSession(claims); // Một token chỉ dùng được một lần; lỗi phía sau sẽ rollback cả việc này.
        UUID userId;
        try {
            userId = jdbc.queryForObject("INSERT INTO users (phone_e164, display_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                    UUID.class, claims.get("phone", String.class), name, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN", "Phone already registered"); // Có người đăng ký trước.
        }
        return success(userId, claims.get("did", String.class), deviceName);
    }

    // ================= Đăng nhập (F-02, F-03) =================

    public AuthSuccessResponse loginPassword(String phone, String password, String deviceId, String deviceName) {
        phone = phone(phone);
        String deviceHash = deviceHash(deviceId);
        throttle.check("PASSWORD_LOGIN", phone, deviceHash);
        account.purgeExpiredDeletions();
        List<Object[]> rows = jdbc.query("SELECT id, password_hash FROM users WHERE phone_e164 = ? AND status = 'ACTIVE'",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}, phone);
        boolean ok = password != null && encoder.matches(password, rows.isEmpty() ? dummyHash : (String) rows.get(0)[1]);
        if (!ok || rows.isEmpty()) {
            throttle.fail("PASSWORD_LOGIN", phone, deviceHash);
            // Lỗi chung, không nói số có tồn tại hay không.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong phone or password");
        }
        throttle.reset("PASSWORD_LOGIN", phone, deviceHash);
        return success((UUID) rows.get(0)[0], deviceHash, deviceName);
    }

    /** Luôn trả 200 để không lộ số có tài khoản; số lạ nhận challengeId giả không bao giờ xác minh được. */
    public OtpChallengeResponse requestLoginOtp(String phone, String deviceId) {
        return requestOtpForExisting(phone, deviceId, "LOGIN");
    }

    public AuthSuccessResponse verifyLoginOtp(String challengeId, String code, String deviceName) {
        OtpService.Verified v = otp.verify(challengeId, code, "LOGIN");
        UUID userId = findActiveUserId(v.phone());
        if (userId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP invalid");
        return success(userId, v.deviceHash(), deviceName);
    }

    // ================= Quên mật khẩu (F-04) =================

    public OtpChallengeResponse requestPasswordResetOtp(String phone, String deviceId) {
        return requestOtpForExisting(phone, deviceId, "PASSWORD_RESET");
    }

    public String verifyPasswordResetOtp(String challengeId, String code) {
        OtpService.Verified v = otp.verify(challengeId, code, "PASSWORD_RESET");
        UUID userId = findActiveUserId(v.phone());
        if (userId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP invalid");
        UUID sid = openSession(userId, v.deviceHash(), "RESET");
        return jwt.issueTempToken(JwtService.RESET, sid, v.phone(), userId, v.deviceHash());
    }

    /** Đặt mật khẩu mới, đăng xuất mọi thiết bị khác và đăng nhập thiết bị hiện tại. */
    @Transactional
    public AuthSuccessResponse completePasswordReset(String resetToken, String newPassword, String deviceName) {
        AccountService.requireStrong(newPassword);
        Claims claims = parseTemp(resetToken, JwtService.RESET);
        consumeSession(claims);
        UUID userId = UUID.fromString(claims.get("uid", String.class));
        String deviceHash = claims.get("did", String.class);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(newPassword), userId);
        jdbc.update("UPDATE user_devices SET token_valid_after = ? WHERE user_id = ? AND device_id_hash <> ?", now(), userId, deviceHash);
        return success(userId, deviceHash, deviceName);
    }

    // ================= Đăng xuất (F-06) =================

    /** Vô hiệu hoá mọi token đã cấp cho thiết bị này. */
    public void logout(AuthenticatedActor actor) {
        jdbc.update("UPDATE user_devices SET token_valid_after = ? WHERE user_id = ? AND device_id_hash = ?",
                now(), actor.userId(), actor.deviceIdHash());
    }

    // ================= Hàm phụ =================

    private OtpChallengeResponse requestOtpForExisting(String phone, String deviceId, String purpose) {
        phone = phone(phone);
        String deviceHash = deviceHash(deviceId);
        if (findActiveUserId(phone) == null) {
            return new OtpChallengeResponse(UUID.randomUUID().toString(), OtpService.EXPIRES_IN_SECONDS, null);
        }
        return challenge(otp.issue(phone, purpose, deviceHash));
    }

    private OtpChallengeResponse challenge(OtpService.Issued issued) {
        return new OtpChallengeResponse(issued.challengeId().toString(), OtpService.EXPIRES_IN_SECONDS,
                otp.demoMode() ? issued.code() : null);
    }

    /** Ghi/cập nhật thiết bị rồi cấp JWT 30 ngày. token_valid_after đặt TRƯỚC khi cấp token nên token mới luôn hợp lệ. */
    private AuthSuccessResponse success(UUID userId, String deviceHash, String deviceName) {
        String name = deviceName == null || deviceName.isBlank() ? "Android" : deviceName.trim();
        jdbc.update("INSERT INTO user_devices (user_id, device_id_hash, device_name, trusted, token_valid_after) "
                + "VALUES (?, ?, ?, TRUE, ?) "
                + "ON CONFLICT (user_id, device_id_hash) DO UPDATE SET device_name = EXCLUDED.device_name, "
                + "last_seen_at = now(), revoked_at = NULL, "
                // GREATEST: đăng nhập lại ngay sau khi đăng xuất không được làm token cũ sống lại.
                + "token_valid_after = GREATEST(user_devices.token_valid_after, EXCLUDED.token_valid_after)",
                userId, deviceHash, name.length() > 100 ? name.substring(0, 100) : name, Timestamp.from(Instant.now().minusSeconds(2)));
        return new AuthSuccessResponse("COMPLETE", jwt.issueAccessToken(userId, deviceHash), account.getProfile(userId));
    }

    /**
     * Mở phiên chờ ở bảng auth_sessions. Schema chỉ cho phép NEED_OTP/COMPLETE/FAILED/LOCKED nên NEED_OTP đóng vai
     * "đã qua OTP, đang chờ bước cuối"; bước cuối chuyển sang COMPLETE bằng một UPDATE nguyên tử.
     */
    private UUID openSession(UUID userId, String deviceHash, String method) {
        return jdbc.queryForObject("INSERT INTO auth_sessions (user_id, device_id_hash, method, state, expires_at) "
                + "VALUES (?, ?, ?, 'NEED_OTP', now() + interval '10 minutes') RETURNING id", UUID.class, userId, deviceHash, method);
    }

    private void consumeSession(Claims claims) {
        int n = jdbc.update("UPDATE auth_sessions SET state = 'COMPLETE' WHERE id = ? AND device_id_hash = ? "
                + "AND state = 'NEED_OTP' AND expires_at > now()",
                UUID.fromString(claims.getSubject()), claims.get("did", String.class));
        if (n == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "Token already used or expired");
    }

    private Claims parseTemp(String token, String expectedType) {
        try {
            Claims claims = jwt.parse(token);
            if (!expectedType.equals(claims.get("typ", String.class))) throw new JwtException("wrong type");
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "Token invalid or expired");
        }
    }

    /** Mốc thời gian theo đồng hồ của server, dùng cho token_valid_after để so được với iatMs của token. */
    private static Timestamp now() { return Timestamp.from(Instant.now()); }

    private UUID findActiveUserId(String phone) {
        account.purgeExpiredDeletions(); // Tài khoản hết hạn chờ xoá không được tính là còn hoạt động.
        List<UUID> ids = jdbc.query("SELECT id FROM users WHERE phone_e164 = ? AND status = 'ACTIVE'",
                (rs, i) -> rs.getObject(1, UUID.class), phone);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Số phải là E.164 (khớp ràng buộc của CSDL); app đã chuẩn hoá nhưng server không tin đầu vào. */
    private static String phone(String phone) {
        if (phone == null || !phone.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PHONE_INVALID", "Phone must be E.164");
        }
        return phone;
    }

    private static String deviceHash(String deviceId) {
        if (deviceId == null || deviceId.length() < 8 || deviceId.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "deviceId is required");
        }
        return HashUtils.sha256Hex(deviceId);
    }
}
