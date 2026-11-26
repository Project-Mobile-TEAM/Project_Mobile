package com.utechat.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đọc "Authorization: Bearer ..." và chỉ đăng nhập request khi token còn hiệu lực VÀ thiết bị chưa bị thu hồi.
 * Đồng thời chặn tuyệt đối việc đặt JWT trong query parameter trên endpoint /ws (DEPLOYMENT_SECURITY.md).
 * Không phải @Component: SecurityConfig tự thêm vào chuỗi lọc để không bị đăng ký hai lần.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    /** Các tên query parameter nhạy cảm tuyệt đối cấm xuất hiện trên URL /ws và /api/media/**. */
    public static final Set<String> FORBIDDEN_WS_QUERY_KEYS = Set.of(
            "token", "jwt", "access_token", "bearer", "authorization", "ticket");

    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Chặn sớm mọi yêu cầu /ws hoặc /api/media/** mang JWT/ticket trong URL query (kể cả có hay không có header Bearer),
        // tuyệt đối không ghi log query string (DEPLOYMENT_SECURITY.md, D-01).
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        boolean tokenGuardedPath = "/ws".equals(path) || (path != null && path.startsWith("/api/media"));
        boolean hasForbiddenQuery = containsForbiddenQueryKey(request.getQueryString())
                || ("GET".equalsIgnoreCase(request.getMethod()) && containsForbiddenParameterName(request));
        if (tokenGuardedPath && hasForbiddenQuery) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":{\"code\":\"VALIDATION\",\"message\":\"Token must not be placed in URL\"}}");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthCheck check = authenticate(jwt.parse(header.substring(7)));
                if (check != null) {
                    request.setAttribute(WsAuthMeta.REQ_ATTR_WS_META, check.wsMeta());
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(check.actor(), null, List.of()));
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Token sai/hết hạn: bỏ qua, Spring Security sẽ trả 401 ở endpoint cần xác thực.
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean containsForbiddenParameterName(HttpServletRequest request) {
        var names = request.getParameterNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && FORBIDDEN_WS_QUERY_KEYS.contains(name.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Kiểm tra chuỗi query thô có chứa khóa nhạy cảm hay không (không phân biệt hoa thường, không log). */
    public static boolean containsForbiddenQueryKey(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return false;
        }
        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = (eq >= 0 ? part.substring(0, eq) : part).trim().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_WS_QUERY_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Trả null nếu token không phải ACCESS, thiết bị bị thu hồi/đăng xuất, hoặc tài khoản không còn ACTIVE. */
    private AuthCheck authenticate(Claims claims) {
        if (!JwtService.ACCESS.equals(claims.get("typ", String.class))) {
            return null;
        }
        UUID userId = UUID.fromString(claims.getSubject());
        String deviceHash = claims.get("did", String.class);
        Number issuedMsNum = claims.get("iatMs", Number.class);
        Date expiration = claims.getExpiration();
        if (deviceHash == null || issuedMsNum == null || expiration == null) {
            return null;
        }
        long issuedAtMs = issuedMsNum.longValue();
        Instant expiresAt = expiration.toInstant();

        List<UUID> validDeviceRowIds = jdbc.query(
                "SELECT d.id AS device_row_id, d.token_valid_after, d.revoked_at, u.status "
                        + "FROM user_devices d JOIN users u ON u.id = d.user_id "
                        + "WHERE d.user_id = ? AND d.device_id_hash = ?",
                (rs, i) -> {
                    boolean notRevoked = rs.getTimestamp("revoked_at") == null;
                    boolean active = "ACTIVE".equals(rs.getString("status"));
                    boolean fresh = issuedAtMs >= rs.getTimestamp("token_valid_after").toInstant().toEpochMilli();
                    return (notRevoked && active && fresh) ? rs.getObject("device_row_id", UUID.class) : null;
                },
                userId, deviceHash);

        if (validDeviceRowIds.isEmpty() || validDeviceRowIds.get(0) == null) {
            return null;
        }
        return new AuthCheck(
                new AuthenticatedActor(userId, deviceHash),
                new WsAuthMeta(validDeviceRowIds.get(0), issuedAtMs, expiresAt));
    }

    private record AuthCheck(AuthenticatedActor actor, WsAuthMeta wsMeta) {
    }
}
