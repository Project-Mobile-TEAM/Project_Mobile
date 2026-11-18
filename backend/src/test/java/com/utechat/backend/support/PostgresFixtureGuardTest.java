package com.utechat.backend.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Guard chạy trước đăng nhập hoặc CREATE DATABASE; không cần PostgreSQL và không dùng mật khẩu .env. */
class PostgresFixtureGuardTest {
    private final String previous = System.getProperty("phase4.pg.url");

    @AfterEach void restore() {
        if (previous == null) System.clearProperty("phase4.pg.url");
        else System.setProperty("phase4.pg.url", previous);
    }

    private void rejects(String url) {
        System.setProperty("phase4.pg.url", url);
        assertThrows(IllegalArgumentException.class, PostgresFixture::new);
    }

    @Test void missingUrlDoesNotFallBackToEnvironment() {
        System.clearProperty("phase4.pg.url");
        assertThrows(IllegalStateException.class, PostgresFixture::new);
    }

    @Test void rejectsRemoteHost() { rejects("jdbc:postgresql://example.test:5432/postgres"); }
    @Test void rejectsUnspecifiedPort() { rejects("jdbc:postgresql://127.0.0.1/postgres"); }
    @Test void rejectsOtherAdminDatabase() { rejects("jdbc:postgresql://127.0.0.1:5432/shared_database"); }
    @Test void rejectsCredentialsInUrl() { rejects("jdbc:postgresql://user:password@127.0.0.1:5432/postgres"); }
    @Test void rejectsOtherProtocol() { rejects("jdbc:h2:mem:test"); }
    @Test void rejectsConnectionOptionsThatCouldRedirectTheFixture() {
        rejects("jdbc:postgresql://127.0.0.1:5432/postgres?service=production");
        rejects("jdbc:postgresql://127.0.0.1:5432/postgres#other");
    }
}
