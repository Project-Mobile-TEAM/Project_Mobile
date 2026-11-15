package com.utechat.backend.config;

import com.utechat.backend.security.JwtAuthenticationFilter;
import com.utechat.backend.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Spring Security không trạng thái: Bearer JWT, không session, không CSRF (API cho app Android). */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, JdbcTemplate jdbc) throws Exception {
        http.csrf(csrf -> csrf.disable()) // Không dùng cookie nên CSRF không áp dụng.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/api/auth/logout").authenticated() // Đăng xuất cần biết thiết bị nào.
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated())
            // Chưa/không xác thực được: 401 dạng JSON chuẩn để AuthInterceptor phía app đưa về màn Đăng nhập.
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.getWriter().write("{\"error\":{\"code\":\"SESSION_EXPIRED\",\"message\":\"Unauthorized\"}}");
            }))
            .addFilterBefore(new JwtAuthenticationFilter(jwt, jdbc), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** BCrypt cost 10 (mật khẩu băm BCrypt, không ghi log). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
