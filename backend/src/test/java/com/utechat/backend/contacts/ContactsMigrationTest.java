package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.support.PostgresFixture;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Kiểm chính V4 đã merge bằng PostgreSQL và Flyway, không thay migration đã áp dụng. */
class ContactsMigrationTest {
    private UUID user(PostgresFixture db) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO users(id,display_name,password_hash) VALUES (?,?,'test')", id, "Test");
        return id;
    }

    private void legacy(PostgresFixture db, UUID a, UUID b, boolean blocked) {
        db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,blocked) VALUES (?,?,?)", a, b, blocked);
    }

    private boolean friend(PostgresFixture db, UUID a, UUID b) {
        return db.jdbc.queryForObject("SELECT is_friend FROM contacts WHERE owner_id=? AND contact_user_id=?", Boolean.class, a, b);
    }

    /** Database mới có đủ V1/V3/V4, không seed demo; chạy lại không làm đổi checksum hoặc dữ liệu. */
    @Test void freshDatabaseMigratesWithoutDemoAndUsesRequiredDefault() throws Exception {
        try (var db = new PostgresFixture()) {
            db.migrate();
            assertEquals(13, db.jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
            var column = db.jdbc.queryForMap("SELECT data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='contacts' AND column_name='is_friend'");
            assertEquals("boolean", column.get("data_type"));
            assertEquals("NO", column.get("is_nullable"));
            assertEquals("false", column.get("column_default"));
            UUID a = user(db), b = user(db);
            legacy(db, a, b, true);
            assertFalse(friend(db, a, b));
            assertEquals(0, db.flyway(null).migrate().migrationsExecuted);
            db.flyway(null).validate();
        }
    }

    /** V4 giữ blocked và coi dòng blocked cũ là block-only, kể cả chiều ngược còn không chặn. */
    @Test void legacyBackfillFollowsMergedConservativePolicyWithoutUnblocking() throws Exception {
        try (var db = new PostgresFixture()) {
            db.flyway("1").migrate();
            UUID a = user(db), b = user(db), c = user(db);
            legacy(db, a, b, true);
            legacy(db, b, a, false);
            legacy(db, b, c, false);
            legacy(db, c, b, false);
            db.migrate();
            assertFalse(friend(db, a, b));
            assertTrue(friend(db, b, a));
            assertTrue(friend(db, b, c));
            assertTrue(friend(db, c, b));
            assertTrue(db.jdbc.queryForObject("SELECT blocked FROM contacts WHERE owner_id=? AND contact_user_id=?", Boolean.class, a, b));
        }
    }

    /** Hai chiều chặn cũ không có đủ thông tin: không ném lỗi như nháp cũ, không suy từ lịch sử ACCEPTED. */
    @Test void mutualLegacyBlocksStayBlockOnlyRegardlessOfAcceptedHistory() throws Exception {
        try (var db = new PostgresFixture()) {
            db.flyway("1").migrate();
            UUID a = user(db), b = user(db);
            legacy(db, a, b, true);
            legacy(db, b, a, true);
            db.jdbc.update("INSERT INTO contact_requests(requester_id,addressee_id,status) VALUES (?,?,'ACCEPTED')", a, b);
            assertDoesNotThrow(db::migrate);
            assertFalse(friend(db, a, b));
            assertFalse(friend(db, b, a));
            assertEquals(2, db.jdbc.queryForObject("SELECT count(*) FROM contacts WHERE blocked", Integer.class));
        }
    }

    /** Cờ đã biết trước khi V4 chạy không bị backfill ghi đè, kể cả bạn bè đang chặn nhau. */
    @Test void existingExplicitFriendFlagsArePreserved() throws Exception {
        try (var db = new PostgresFixture()) {
            db.flyway("1").migrate();
            UUID a = user(db), b = user(db);
            db.jdbc.execute("ALTER TABLE contacts ADD COLUMN is_friend BOOLEAN");
            db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,blocked,is_friend) VALUES (?,?,TRUE,TRUE),(?,?,TRUE,TRUE)", a, b, b, a);
            db.migrate();
            assertTrue(friend(db, a, b));
            assertTrue(friend(db, b, a));
        }
    }

    /** Tạo lỗi CHECK có SQLSTATE cụ thể trong backfill để kiểm rollback; không bắt RuntimeException mơ hồ. */
    @Test void failedBackfillRollsBackAndCanBeRetriedWithoutRepair() throws Exception {
        try (var db = new PostgresFixture()) {
            db.flyway("1").migrate();
            UUID a = user(db), b = user(db);
            db.jdbc.execute("ALTER TABLE contacts ADD COLUMN is_friend BOOLEAN");
            legacy(db, a, b, false);
            db.jdbc.execute("ALTER TABLE contacts ADD CONSTRAINT test_reject_backfill CHECK (is_friend IS NOT TRUE)");
            var error = assertThrows(FlywayException.class, db::migrate);
            Throwable cause = error;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertInstanceOf(SQLException.class, cause);
            assertEquals("23514", ((SQLException) cause).getSQLState());
            assertNull(db.jdbc.queryForObject("SELECT is_friend FROM contacts", Boolean.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='4'", Integer.class));
            db.jdbc.execute("ALTER TABLE contacts DROP CONSTRAINT test_reject_backfill");
            db.migrate();
            assertTrue(friend(db, a, b));
        }
    }

    /** Database nạp tay V1 được baseline rồi nâng cấp, không tạo lại bảng hoặc xóa tài khoản cũ. */
    @Test void manualV1DatabaseIsBaselinedAndUpgraded() throws Exception {
        try (var db = new PostgresFixture()) {
            try (var c = db.source.getConnection()) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            }
            UUID a = user(db);
            db.migrate();
            assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, a));
            assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE type='BASELINE'", Integer.class));
            db.flyway(null).validate();
        }
    }
}
