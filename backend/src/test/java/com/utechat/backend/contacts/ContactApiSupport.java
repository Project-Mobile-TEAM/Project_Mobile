package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.config.SecurityConfig;
import com.utechat.backend.controller.ContactController;
import com.utechat.backend.exception.GlobalExceptionHandler;
import com.utechat.backend.security.JwtService;
import com.utechat.backend.service.*;
import com.utechat.backend.support.PostgresFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** HTTP qua JWT/filter thật và PostgreSQL cô lập; không dùng authentication() để bỏ qua chữ ký. */
abstract class ContactApiSupport {
    static final String SECRET = "phase4-contact-api-test-secret-not-a-production-key";
    static final String PHONE_A = "+84901111111", PHONE_B = "+84902222222", PHONE_C = "+84903333333";
    PostgresFixture db;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    JwtService jwt;
    ContactsService service;
    UUID a, b, c;
    private final Map<UUID, String> tokens = new HashMap<>();

    // Profile riêng ngăn cấu hình thử làm thay đổi ứng dụng dev khi dùng test classpath.
    @TestConfiguration(proxyBeanMethods = false) @Profile("phase4-contact-api")
    @EnableWebSecurity @EnableWebMvc @EnableTransactionManagement
    static class Web { }

    @BeforeEach void setupApi() throws Exception {
        db = new PostgresFixture();
        db.migrate();
        a = user(PHONE_A, "Người A"); b = user(PHONE_B, "Người B"); c = user(PHONE_C, "Người C");
        jwt = new JwtService(SECRET, 3600);
        for (UUID user : List.of(a, b, c)) tokens.put(user, jwt.issueAccessToken(user, "test-device"));
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("phase4-contact-api");
        context.register(Web.class, SecurityConfig.class, ContactController.class, GlobalExceptionHandler.class);
        // Clock cố định chỉ cho limiter; JWT vẫn dùng đồng hồ thật của JwtService Phase 3.
        var limiter = new LookupRateLimiter(Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC));
        var manager = new DataSourceTransactionManager(db.source);
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jdbcTemplate", db.jdbc);
            factory.registerSingleton("jwtService", jwt);
            factory.registerSingleton("transactionManager", manager);
            factory.registerSingleton("objectMapper", json);
            factory.registerSingleton("contactPolicyService", new ContactPolicyService(db.jdbc));
            factory.registerSingleton("lookupRateLimiter", limiter);
        });
        context.register(ContactsService.class, SyncEventWriter.class);
        context.refresh();
        service = context.getBean(ContactsService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach void closeApi() throws Exception {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
        if (db != null) db.close();
    }

    private UUID user(String phone, String name) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO users(id,phone_e164,display_name,password_hash) VALUES (?,?,?,'test')", id, phone, name);
        db.jdbc.update("INSERT INTO user_devices(user_id,device_id_hash,token_valid_after) VALUES (?,'test-device','2000-01-01T00:00:00Z')", id);
        return id;
    }

    /** Trả JSON response UTF-8; dữ liệu người gửi chỉ được chọn bằng Bearer token của fixture. */
    JsonNode call(UUID actor, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + tokens.get(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        return response.getContentAsByteArray().length == 0 ? null : json.readTree(response.getContentAsByteArray());
    }

    void code(JsonNode result, String expected) { assertEquals(expected, result.path("error").path("code").asText()); }
    String token(UUID actor) { return tokens.get(actor); }
    int events() { return db.jdbc.queryForObject("SELECT count(*) FROM sync_events", Integer.class); }
    int requests() { return db.jdbc.queryForObject("SELECT count(*) FROM contact_requests", Integer.class); }

    /** Seed SQL chỉ dùng để tạo điều kiện cho 03/04, không gọi các API 05–09 ngoài phạm vi. */
    void contact(UUID owner, UUID other, boolean friend, boolean blocked) {
        db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,?,?)", owner, other, friend, blocked);
    }

    UUID pending(UUID sender, UUID recipient) {
        return db.jdbc.queryForObject("INSERT INTO contact_requests(requester_id,addressee_id) VALUES (?,?) RETURNING id", UUID.class, sender, recipient);
    }
}
