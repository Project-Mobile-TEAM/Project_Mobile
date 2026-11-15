package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import com.utechat.backend.otp.OtpDelivery;
import com.utechat.backend.util.HashUtils;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * OTP 6 số (ADR-002): chỉ lưu bản băm SHA-256, hết hạn sau 5 phút, tối đa 5 lần nhập sai mỗi mã,
 * gửi lại cách nhau tối thiểu 60 giây và tối đa 5 lần mỗi giờ mỗi số. Mỗi mã chỉ dùng được MỘT lần (chống replay).
 * Không chạy trong transaction của caller để số lần nhập sai luôn được ghi lại.
 */
@Service
public class OtpService {
    public static final int EXPIRES_IN_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 5, MAX_PER_HOUR = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final OtpDelivery delivery;
    private final boolean demoMode;
    private final int cooldownSeconds;

    public OtpService(JdbcTemplate jdbc, OtpDelivery delivery, @Value("${app.demo-mode:false}") boolean demoMode,
            @Value("${app.otp.cooldown-seconds:60}") int cooldownSeconds) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.demoMode = demoMode;
        this.cooldownSeconds = cooldownSeconds;
    }

    /** Mã vừa phát hành; code chỉ dùng để gửi và (ở demo) trả trong response. */
    public record Issued(UUID challengeId, String code) { }
    /** Kết quả xác minh thành công: số điện thoại và hash thiết bị đã gắn với mã lúc phát hành. */
    public record Verified(String phone, String deviceHash) { }

    public boolean demoMode() { return demoMode; }

    /** Phát hành OTP cho số; vi phạm giãn cách hoặc quá 5 lần/giờ thì 429 OTP_RATE_LIMIT. */
    public Issued issue(String phone, String purpose, String deviceHash) {
        List<Long> sinceLast = jdbc.query(
                "SELECT CEIL(EXTRACT(EPOCH FROM (now() - created_at)))::bigint FROM otp_challenges "
                        + "WHERE phone_e164 = ? ORDER BY created_at DESC LIMIT 1", (rs, i) -> rs.getLong(1), phone);
        if (!sinceLast.isEmpty() && sinceLast.get(0) < cooldownSeconds) {
            throw rateLimit(cooldownSeconds - sinceLast.get(0));
        }
        Integer lastHour = jdbc.queryForObject(
                "SELECT count(*) FROM otp_challenges WHERE phone_e164 = ? AND created_at > now() - interval '1 hour'",
                Integer.class, phone);
        if (lastHour != null && lastHour >= MAX_PER_HOUR) throw rateLimit(3600);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000)); // SecureRandom, đệm 0 cho đủ 6 số.
        UUID id = jdbc.queryForObject(
                "INSERT INTO otp_challenges (phone_e164, purpose, device_id_hash, code_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?, now() + make_interval(secs => ?)) RETURNING id",
                UUID.class, phone, purpose, deviceHash, HashUtils.sha256Hex(code), EXPIRES_IN_SECONDS);
        delivery.send(phone, code);
        return new Issued(id, code);
    }

    /**
     * Xác minh mã bằng MỘT câu UPDATE nguyên tử (đánh dấu đã dùng nếu đúng mã, còn hạn, chưa dùng, còn lượt thử).
     * Nếu không khớp, đọc lại để biết lý do và tăng số lần sai.
     */
    public Verified verify(String challengeId, String code, String purpose) {
        UUID id = parseId(challengeId);
        if (id == null || code == null || !code.matches("\\d{6}")) throw invalid();
        List<Verified> ok = jdbc.query(
                "UPDATE otp_challenges SET consumed_at = now() WHERE id = ? AND purpose = ? AND code_hash = ? "
                        + "AND consumed_at IS NULL AND expires_at > now() AND attempts < ? RETURNING phone_e164, device_id_hash",
                (rs, i) -> new Verified(rs.getString(1), rs.getString(2)), id, purpose, HashUtils.sha256Hex(code), MAX_ATTEMPTS);
        if (!ok.isEmpty()) return ok.get(0);

        // Thất bại: xác định lý do để trả đúng mã lỗi.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT consumed_at IS NOT NULL AS consumed, expires_at <= now() AS expired, attempts FROM otp_challenges "
                        + "WHERE id = ? AND purpose = ?", id, purpose);
        if (rows.isEmpty() || (Boolean) rows.get(0).get("consumed")) throw invalid();
        if ((Boolean) rows.get(0).get("expired")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "OTP expired");
        }
        if (((Number) rows.get(0).get("attempts")).intValue() >= MAX_ATTEMPTS) throw tooManyAttempts();
        // Sai mã: cộng một lần thử; đủ 5 lần thì lần sau mã này không còn dùng được.
        Integer attempts = jdbc.queryForObject(
                "UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ? RETURNING attempts", Integer.class, id);
        throw attempts != null && attempts >= MAX_ATTEMPTS ? tooManyAttempts() : invalid();
    }

    private static UUID parseId(String s) {
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP invalid"); }
    private static ApiException tooManyAttempts() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS", "Too many wrong attempts"); }
    private static ApiException rateLimit(long retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMIT", "Too many OTP requests",
                Map.of("retryAfterSeconds", retryAfter));
    }
}
