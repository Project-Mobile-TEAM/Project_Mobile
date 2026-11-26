package com.utechat.backend.service;

import com.utechat.backend.dto.CallDtos.IceServerDto;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Dịch vụ cấp phát cấu hình ICE (STUN/TURN/TURNS) theo chuẩn coturn REST HMAC-SHA1 (D-09, ADR-013):
 * - Chỉ đọc COTURN_SHARED_SECRET từ cấu hình backend/biến môi trường, tuyệt đối không trả về client hay ghi log.
 * - Khi cấu hình đủ URL và shared-secret: sinh username = "<expiryEpochSeconds>:<userId>" và
 *   credential = Base64(HMAC-SHA1(sharedSecret, username)) cho các URL TURN/TURNS; URL STUN không kèm credential.
 * - Khi chưa cấu hình (môi trường dev/LAN cục bộ): tự động fallback về STUN công khai (mặc định Google STUN).
 */
@Service
public class TurnCredentialsService {
    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final String DEFAULT_FALLBACK_STUN = "stun:stun.l.google.com:19302";
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private final Clock clock;
    private final List<String> configuredUrls;
    private final String sharedSecret;
    private final long ttlSeconds;
    private final List<String> fallbackStunUrls;

    public TurnCredentialsService(
            Clock clock,
            @Value("${app.coturn.urls:}") String rawUrls,
            @Value("${app.coturn.shared-secret:}") String sharedSecret,
            @Value("${app.coturn.ttl-seconds:3600}") long ttlSeconds,
            @Value("${app.coturn.fallback-stun-urls:stun:stun.l.google.com:19302}") String rawFallbackStunUrls) {
        this.clock = clock;
        this.configuredUrls = parseCsv(rawUrls);
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        List<String> parsedFallback = parseCsv(rawFallbackStunUrls);
        this.fallbackStunUrls = parsedFallback.isEmpty() ? List.of(DEFAULT_FALLBACK_STUN) : parsedFallback;
    }

    /** Sinh danh sách ICE server cho người dùng cụ thể với credential tạm thời có hạn dùng. */
    public List<IceServerDto> generateIceServers(UUID userId) {
        if (configuredUrls.isEmpty() || sharedSecret.isEmpty()) {
            // Môi trường local/LAN chưa cấu hình coturn: trả về STUN công khai không kèm credential.
            return List.of(new IceServerDto(fallbackStunUrls, null, null));
        }

        List<String> stunUrls = new ArrayList<>();
        List<String> turnUrls = new ArrayList<>();
        for (String url : configuredUrls) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("stun:") || lower.startsWith("stuns:")) {
                stunUrls.add(url);
            } else {
                turnUrls.add(url);
            }
        }

        List<IceServerDto> result = new ArrayList<>();
        if (!stunUrls.isEmpty()) {
            result.add(new IceServerDto(List.copyOf(stunUrls), null, null));
        }
        if (!turnUrls.isEmpty()) {
            long expiryEpoch = clock.instant().getEpochSecond() + ttlSeconds;
            String username = expiryEpoch + ":" + userId;
            String credential = computeHmacSha1Base64(sharedSecret, username);
            result.add(new IceServerDto(List.copyOf(turnUrls), username, credential));
        }
        if (result.isEmpty()) {
            return List.of(new IceServerDto(fallbackStunUrls, null, null));
        }
        return List.copyOf(result);
    }

    /** Tính Base64(HMAC-SHA1(secret, username)) theo đặc tả coturn REST API. */
    private static String computeHmacSha1Base64(String secret, String username) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot compute HMAC-SHA1 TURN credential", e);
        }
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return List.copyOf(items);
    }
}
