package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.util.HashUtils;
import com.utechat.backend.util.Totp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 2FA TOTP kèm 8 mã dự phòng (ADR-004). Khoá bí mật mã hoá AES-GCM; mã dự phòng chỉ lưu bản băm SHA-256.
 * Kiểm mã dùng ThrottleService (purpose TWO_FACTOR) và KHÔNG nằm trong transaction để lần sai vẫn được ghi.
 */
@Service
public class TwoFactorService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Bỏ ký tự dễ nhầm: I, O, 0, 1.
    private static final int BACKUP_COUNT = 8, BACKUP_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AccountService account;
    private final ThrottleService throttle;
    private final TransactionTemplate tx;
    private final SecretKeySpec aesKey;

    public TwoFactorService(JdbcTemplate jdbc, AccountService account, ThrottleService throttle, TransactionTemplate tx,
            @Value("${app.totp.encryption-key:}") String encryptionKey, @Value("${jwt.secret}") String jwtSecret) {
        this.jdbc = jdbc;
        this.account = account;
        this.throttle = throttle;
        this.tx = tx;
        // shortcut: chưa đặt TOTP_ENCRYPTION_KEY thì suy ra từ JWT_SECRET; đổi JWT_SECRET sẽ làm hỏng mọi khoá 2FA đã lưu, nên production phải đặt khoá riêng.
        String material = encryptionKey.isBlank() ? jwtSecret + ":totp" : encryptionKey;
        this.aesKey = new SecretKeySpec(HashUtils.sha256Bytes(material), "AES");
    }

    public record Setup(String secret, String otpauthUri) { }

    public boolean isEnabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM totp_credentials WHERE user_id = ? AND confirmed_at IS NOT NULL)", Boolean.class, userId));
    }

    /** Bước 1 (F-05): nhập lại mật khẩu, tạo khoá mới chưa xác nhận (ghi đè khoá dở dang trước đó). */
    public Setup setup(AuthenticatedActor actor, String password) {
        account.requirePassword(actor.userId(), password);
        if (isEnabled(actor.userId())) throw new ApiException(HttpStatus.CONFLICT, "TWO_FACTOR_ALREADY_ENABLED", "2FA already enabled");
        String secret = Totp.newSecret();
        jdbc.update("INSERT INTO totp_credentials (user_id, secret_enc) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, last_used_step = NULL, created_at = now()",
                actor.userId(), encrypt(secret));
        String phone = phone(actor.userId());
        String uri = "otpauth://totp/UTEChat:" + URLEncoder.encode(phone, StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=UTEChat";
        return new Setup(secret, uri);
    }

    /** Bước 2: nhập thử một mã; đúng thì bật 2FA và trả 8 mã dự phòng (chỉ hiện một lần). */
    public List<String> enable(AuthenticatedActor actor, String code) {
        String phone = phone(actor.userId());
        throttle.check("TWO_FACTOR", phone, actor.deviceIdHash());
        List<String> enc = jdbc.query("SELECT secret_enc FROM totp_credentials WHERE user_id = ? AND confirmed_at IS NULL",
                (rs, i) -> rs.getString(1), actor.userId());
        if (enc.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "TWO_FACTOR_NOT_SETUP", "Call setup first");
        long step = code != null && code.matches("\\d{6}") ? Totp.match(decrypt(enc.get(0)), code, Totp.stepNow()) : -1;
        if (step < 0) {
            throttle.fail("TWO_FACTOR", phone, actor.deviceIdHash());
            throw invalid();
        }
        throttle.reset("TWO_FACTOR", phone, actor.deviceIdHash());
        return tx.execute(st -> confirm(actor.userId(), step));
    }

    private List<String> confirm(UUID userId, long step) {
        jdbc.update("UPDATE totp_credentials SET confirmed_at = now(), last_used_step = ? WHERE user_id = ?", step, userId);
        jdbc.update("DELETE FROM backup_codes WHERE user_id = ?", userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < BACKUP_COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < BACKUP_LENGTH; j++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            codes.add(sb.substring(0, 4) + "-" + sb.substring(4));
            jdbc.update("INSERT INTO backup_codes (user_id, code_hash) VALUES (?, ?)", userId, HashUtils.sha256Hex(sb.toString()));
        }
        return codes;
    }

    /** F-06: cần mật khẩu và một mã 2FA hợp lệ (hoặc mã dự phòng). */
    public void disable(AuthenticatedActor actor, String password, String code) {
        account.requirePassword(actor.userId(), password);
        if (!isEnabled(actor.userId())) throw new ApiException(HttpStatus.CONFLICT, "TWO_FACTOR_NOT_ENABLED", "2FA is not enabled");
        requireValidCode(actor.userId(), phone(actor.userId()), actor.deviceIdHash(), code);
        tx.executeWithoutResult(st -> {
            jdbc.update("DELETE FROM totp_credentials WHERE user_id = ?", actor.userId());
            jdbc.update("DELETE FROM backup_codes WHERE user_id = ?", actor.userId());
        });
    }

    /** Mã TOTP 6 số hoặc mã dự phòng (có hoặc không có dấu gạch). Sai thì ghi một lần sai và ném 400 CODE_INVALID; đang bị khoá thì 423. */
    public void requireValidCode(UUID userId, String phone, String deviceHash, String code) {
        throttle.check("TWO_FACTOR", phone, deviceHash);
        if (code == null || !consume(userId, code.trim().toUpperCase().replace("-", ""))) {
            throttle.fail("TWO_FACTOR", phone, deviceHash);
            throw invalid();
        }
        throttle.reset("TWO_FACTOR", phone, deviceHash);
    }

    /** Tiêu thụ mã bằng UPDATE nguyên tử: một nhịp TOTP hoặc một mã dự phòng chỉ dùng được một lần. */
    private boolean consume(UUID userId, String code) {
        if (code.matches("\\d{6}")) {
            List<String> enc = jdbc.query("SELECT secret_enc FROM totp_credentials WHERE user_id = ? AND confirmed_at IS NOT NULL",
                    (rs, i) -> rs.getString(1), userId);
            if (enc.isEmpty()) return false;
            long step = Totp.match(decrypt(enc.get(0)), code, Totp.stepNow());
            return step >= 0 && jdbc.update("UPDATE totp_credentials SET last_used_step = ? WHERE user_id = ? "
                    + "AND (last_used_step IS NULL OR last_used_step < ?)", step, userId, step) == 1;
        }
        return code.length() == BACKUP_LENGTH && jdbc.update("UPDATE backup_codes SET used_at = now() "
                + "WHERE user_id = ? AND code_hash = ? AND used_at IS NULL", userId, HashUtils.sha256Hex(code)) == 1;
    }

    private String phone(UUID userId) {
        return jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id = ?", String.class, userId);
    }

    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "Code invalid"); }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String decrypt(String enc) {
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
