package com.utechat.backend.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Kiểm URL cấp tường minh và database nhúng thật; không dùng mật khẩu .env. */
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

    @Test void missingUrlStartsIsolatedEmbeddedPostgres() throws Exception {
        System.clearProperty("phase4.pg.url");
        try (var db = new PostgresFixture()) {
            assertTrue(db.source.getUrl().startsWith("jdbc:postgresql://127.0.0.1:"));
            assertTrue(db.source.getPassword().isEmpty());
            assertTrue(db.jdbc.queryForObject("SELECT current_database()", String.class).matches("phase4_[a-f0-9]{32}"));
            assertEquals("127.0.0.1", db.jdbc.queryForObject("SHOW listen_addresses", String.class));
        }
    }

    /** Đóng một fixture phải xóa đúng database của nó, giữ dữ liệu của fixture còn sống. */
    @Test void embeddedDatabasesAreIsolatedAndCloseOnlyDropsOwnedDatabase() throws Exception {
        System.clearProperty("phase4.pg.url");
        try (var survivor = new PostgresFixture()) {
            survivor.jdbc.execute("CREATE TABLE fixture_probe (value integer)");
            survivor.jdbc.update("INSERT INTO fixture_probe VALUES (42)");
            String dropped;
            try (var other = new PostgresFixture()) {
                dropped = other.jdbc.queryForObject("SELECT current_database()", String.class);
                assertNotEquals(dropped, survivor.jdbc.queryForObject("SELECT current_database()", String.class));
                assertNull(other.jdbc.queryForObject("SELECT to_regclass('fixture_probe')", String.class));
                assertEquals(survivor.jdbc.queryForObject("SELECT inet_server_port()", Integer.class),
                        other.jdbc.queryForObject("SELECT inet_server_port()", Integer.class));
            }
            assertEquals(0, survivor.jdbc.queryForObject("SELECT count(*) FROM pg_database WHERE datname=?", Integer.class, dropped));
            assertEquals(42, survivor.jdbc.queryForObject("SELECT value FROM fixture_probe", Integer.class));
        }
    }

    @Test void rejectsBlankExplicitUrlInsteadOfSilentlyStartingEmbedded() { rejects(""); }

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
