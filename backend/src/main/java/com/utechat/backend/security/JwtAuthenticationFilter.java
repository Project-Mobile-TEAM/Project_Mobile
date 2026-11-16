package com.utechat.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đọc "Authorization: Bearer ..." và chỉ đăng nhập request khi token còn hiệu lực VÀ thiết bị chưa bị thu hồi.
 * Không phải @Component: SecurityConfig tự thêm vào chuỗi lọc để không bị đăng ký hai lần.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthenticatedActor actor = authenticate(jwt.parse(header.substring(7)));
                if (actor != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(actor, null, List.of()));
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Token sai/hết hạn: bỏ qua, Spring Security sẽ trả 401 ở endpoint cần xác thực.
            }
        }
        chain.doFilter(request, response);
    }

    /** Trả null nếu token không phải ACCESS, thiết bị bị thu hồi/đăng xuất, hoặc tài khoản không còn ACTIVE. */
    private AuthenticatedActor authenticate(Claims claims) {
        // Chặn dùng token tạm (đăng ký / đặt lại mật khẩu) làm token truy cập.
        if (!JwtService.ACCESS.equals(claims.get("typ", String.class))) return null;
        UUID userId = UUID.fromString(claims.getSubject());
        String deviceHash = claims.get("did", String.class);
        List<Boolean> valid = jdbc.query(
                "SELECT d.token_valid_after, d.revoked_at, u.status FROM user_devices d JOIN users u ON u.id = d.user_id "
                        + "WHERE d.user_id = ? AND d.device_id_hash = ?",
                (rs, i) -> {
                    boolean notRevoked = rs.getTimestamp("revoked_at") == null;
                    boolean active = "ACTIVE".equals(rs.getString("status"));
                    // Token cấp trước mốc token_valid_after (đăng xuất, đổi mật khẩu...) bị vô hiệu ngay.
                    Number issuedMs = claims.get("iatMs", Number.class);
                    boolean fresh = issuedMs != null
                            && issuedMs.longValue() >= rs.getTimestamp("token_valid_after").toInstant().toEpochMilli();
                    return notRevoked && active && fresh;
                }, userId, deviceHash);
        return !valid.isEmpty() && valid.get(0) ? new AuthenticatedActor(userId, deviceHash) : null;
    }
}
