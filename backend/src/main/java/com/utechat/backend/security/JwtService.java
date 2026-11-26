package com.utechat.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Ký và kiểm JWT HMAC-SHA256: token truy cập 30 ngày và token tạm 10 phút cho đăng ký / đặt lại mật khẩu. */
@Service
public class JwtService {
    public static final String ACCESS = "ACCESS";
    public static final String REGISTRATION = "REGISTRATION";
    public static final String RESET = "RESET";
    public static final String TWO_FACTOR = "TWO_FACTOR";
    public static final String FRIEND = "FRIEND";
    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-seconds}") long accessSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HMAC-SHA256 cần khoá >= 256 bit; thiếu thì dừng ngay lúc khởi động thay vì chạy với khoá yếu.
        if (bytes.length < 32) throw new IllegalStateException("jwt.secret phải dài ít nhất 32 ký tự (đặt biến JWT_SECRET)");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = Duration.ofSeconds(accessSeconds);
    }

    /**
     * Token truy cập: sub = userId, did = hash của deviceId, typ = ACCESS.
     * iatMs là thời điểm cấp chính xác tới mili giây (claim iat chuẩn chỉ có độ chính xác giây) để so với mốc
     * thu hồi token_valid_after; cả hai đều lấy từ đồng hồ của server nên không phụ thuộc đồng hồ của CSDL.
     */
    public String issueAccessToken(UUID userId, String deviceIdHash) {
        return build(ACCESS, userId.toString(), accessTtl).claim("did", deviceIdHash)
                .claim("iatMs", Instant.now().toEpochMilli()).compact();
    }

    /**
     * Token tạm dùng một lần (tính "một lần" do bảng auth_sessions đảm bảo, không phải chữ ký).
     * sub = id phiên, phone = số đang đăng ký, uid = tài khoản (chỉ với RESET), did = hash thiết bị.
     */
    public String issueTempToken(String type, UUID sessionId, String phone, UUID userId, String deviceIdHash) {
        JwtBuilder builder = build(type, sessionId.toString(), Duration.ofMinutes(10))
                .claim("did", deviceIdHash).claim("phone", phone);
        if (userId != null) builder.claim("uid", userId.toString());
        return builder.compact();
    }

    private JwtBuilder build(String type, String subject, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder().subject(subject).claim("typ", type)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl))).signWith(key);
    }

    /** Kiểm chữ ký và hạn; sai thì ném JwtException. */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
