package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.config.SecurityConfig;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.support.PostgresFixture;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Tái sử dụng nguyên SecurityConfig/JwtService/filter Phase 3; thiết bị và tài khoản đọc từ PostgreSQL thật. */
class ContactJwtPostgresTest {
    private static final String SECRET = "phase4-jwt-test-secret-not-a-production-key";
    private static final String DEVICE = "phase4-test-device";
    private PostgresFixture db;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JwtService jwt;
    private UUID actor;

    // Profile riêng ngăn endpoint thử xuất hiện khi LocalDevServer chạy với test classpath.
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("phase4-foundation-jwt")
    @EnableWebSecurity @EnableWebMvc
    static class Web {
        @Bean Identity identity() { return new Identity(); }
    }

    @RestController @Profile("phase4-foundation-jwt")
    static class Identity {
        @GetMapping("/api/phase4-foundation")
        Map<String, String> identity(@AuthenticationPrincipal AuthenticatedActor actor) {
            return Map.of("id", actor.userId().toString(), "device", actor.deviceIdHash());
        }
    }

    @BeforeEach void setup() throws Exception {
        db = new PostgresFixture();
        db.migrate();
        actor = UUID.randomUUID();
        db.jdbc.update("INSERT INTO users(id,display_name,password_hash) VALUES (?,'Test','test')", actor);
        db.jdbc.update("INSERT INTO user_devices(user_id,device_id_hash,token_valid_after) VALUES (?,?,'2000-01-01T00:00:00Z')", actor, DEVICE);
        jwt = new JwtService(SECRET, 3600);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("phase4-foundation-jwt");
        context.register(Web.class, SecurityConfig.class);
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jdbcTemplate", db.jdbc);
            factory.registerSingleton("jwtService", jwt);
        });
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach void close() throws Exception {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
        if (db != null) db.close();
    }

    /** Endpoint thử đi qua SecurityFilterChain thật; chỉ dùng GET, không gọi API nghiệp vụ mục 03–09. */
    private String call(String token, int status) throws Exception {
        var request = get("/api/phase4-foundation").param("userId", UUID.randomUUID().toString());
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        if (status == 401) assertEquals("SESSION_EXPIRED",
                new ObjectMapper().readTree(response.getContentAsString()).path("error").path("code").asText());
        return response.getContentAsString();
    }

    /** Actor lấy từ chữ ký/token và bảng device, không lấy userId do request tự khai. */
    @Test void validAccessUsesAuthenticatedActor() throws Exception {
        var body = new ObjectMapper().readTree(call(jwt.issueAccessToken(actor, DEVICE), 200));
        assertEquals(actor.toString(), body.path("id").asText());
        assertEquals(DEVICE, body.path("device").asText());
    }

    @Test void missingTokenIsUnauthorized() throws Exception { call(null, 401); }

    /** Chữ ký khác khóa bị từ chối trước khi cấp actor. */
    @Test void wrongSignatureIsUnauthorized() throws Exception {
        call(new JwtService("another-test-secret-not-the-same-production-key", 3600).issueAccessToken(actor, DEVICE), 401);
    }

    /** Token đã hết hạn bị từ chối mà không cần chờ đồng hồ test. */
    @Test void expiredTokenIsUnauthorized() throws Exception {
        call(new JwtService(SECRET, -60).issueAccessToken(actor, DEVICE), 401);
    }

    /** Token đăng ký/đặt lại mật khẩu không được dùng như ACCESS dù cùng khóa ký. */
    @Test void temporaryTokensCannotAuthenticate() throws Exception {
        for (String type : new String[]{JwtService.REGISTRATION, JwtService.RESET}) {
            call(jwt.issueTempToken(type, UUID.randomUUID(), "+84901111111", actor, DEVICE), 401);
        }
    }

    /** Thu hồi thiết bị trong PostgreSQL có hiệu lực với token đang còn hạn. */
    @Test void revokedDeviceIsUnauthorized() throws Exception {
        String token = jwt.issueAccessToken(actor, DEVICE);
        db.jdbc.update("UPDATE user_devices SET revoked_at=now() WHERE user_id=?", actor);
        call(token, 401);
    }

    /** Token cấp trước token_valid_after bị vô hiệu; không chỉ kiểm exp/chữ ký. */
    @Test void invalidatedOldTokenIsUnauthorized() throws Exception {
        String token = jwt.issueAccessToken(actor, DEVICE);
        db.jdbc.update("UPDATE user_devices SET token_valid_after=now()+interval '1 day' WHERE user_id=?", actor);
        call(token, 401);
    }

    /** Tài khoản LOCKED/DELETED không được xác thực dù device và token vẫn còn. */
    @Test void inactiveAccountIsUnauthorized() throws Exception {
        String token = jwt.issueAccessToken(actor, DEVICE);
        for (String status : new String[]{"LOCKED", "DELETED"}) {
            db.jdbc.update("UPDATE users SET status=? WHERE id=?", status, actor);
            call(token, 401);
        }
    }
}
