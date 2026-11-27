package com.utechat.backend.config;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.support.PostgresFixture;
import java.sql.Connection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Kiểm chứng cấu hình Flyway tự động: chuyển Transaction Pooler 6543 sang Session Pooler 5432,
 * baseline an toàn cho database từng nạp V1/V2 thủ công, và bỏ qua V2 seed khi chạy production.
 */
class FlywayMigrationTest {

    @Test
    void v9UpgradesExistingV8AndLocksNewTables() throws Exception {
        try (var database = new PostgresFixture()) {
            DataSource ds = database.source;
            // Nâng từ V8 đang chạy thay vì chỉ kiểm tra database trống.
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("8").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

            assertEquals(3, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
                      AND ((table_name = 'attachments' AND column_name = 'revoked_at')
                        OR (table_name = 'messages' AND column_name IN ('recalled_at', 'sticker_id')))
                    """, Integer.class));
            // Cả năm bảng mới mặc định từ chối Data API vì có RLS và không có policy.
            String tables = "('sticker_packs','stickers','user_sticker_packs','message_deletions','message_reactions')";
            assertEquals(5, jdbc.queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND rowsecurity AND tablename IN " + tables,
                    Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename IN " + tables,
                    Integer.class));
            assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM public.stickers", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM public.sticker_packs", Integer.class));
            assertEquals("ute_daily", jdbc.queryForObject(
                    "SELECT code FROM public.sticker_packs WHERE is_default", String.class));

            // Ghi dữ liệu thật để xác nhận CHECK mở rộng và ràng buộc emoji còn hiệu lực.
            var user = jdbc.queryForObject(
                    "INSERT INTO public.users (display_name, password_hash) VALUES ('V9', 'fixture') RETURNING id",
                    java.util.UUID.class);
            var conversation = jdbc.queryForObject(
                    "INSERT INTO public.conversations (type, title) VALUES ('GROUP', 'V9') RETURNING id",
                    java.util.UUID.class);
            Long message = jdbc.queryForObject("""
                    INSERT INTO public.messages (conversation_id, sender_id, client_id, type, sticker_id)
                    VALUES (?, ?, gen_random_uuid(), 'STICKER', (SELECT id FROM public.stickers LIMIT 1))
                    RETURNING id
                    """, Long.class, conversation, user);
            for (String event : new String[]{"MESSAGE_RECALLED", "MESSAGE_DELETED_FOR_ME", "MESSAGE_REACTION_UPDATED"}) {
                jdbc.update("""
                        INSERT INTO public.sync_events (user_id, event_type, entity_type, entity_id, payload_json)
                        VALUES (?, ?, 'MESSAGE', ?, '{}'::jsonb)
                        """, user, event, message.toString());
            }
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO public.message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?)
                    """, message, user, "🔥"));
        }
    }

    @Test
    void rewritesTransactionPoolerUrlToSessionPooler() {
        String pooler6543 = "jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0";
        assertEquals(
                "jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require",
                FlywaySessionPoolerConfig.toSessionPoolerUrl(pooler6543));

        // URL chỉ chứa prepareThreshold=0 ngay sau dấu '?' vẫn được làm sạch dấu nối hợp lệ.
        String onlyPrepare = "jdbc:postgresql://db.example:6543/postgres?prepareThreshold=0&sslmode=require";
        assertEquals(
                "jdbc:postgresql://db.example:5432/postgres?sslmode=require",
                FlywaySessionPoolerConfig.toSessionPoolerUrl(onlyPrepare));
    }

    @Test
    void migratesPreexistingManualDatabaseWithBaselineAndAppliesV6() throws Exception {
        try (var database = new PostgresFixture()) {
            DataSource ds = database.source;
            // Mô phỏng database Supabase cũ chỉ mới chạy tay V1 trong SQL Editor (chưa có flyway_schema_history).
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource("src/main/resources/db/migration/V1__initial_schema.sql"));
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .ignoreMigrationPatterns("*:missing")
                    .load();
            flyway.migrate();

            JdbcTemplate jdbc = new JdbcTemplate(ds);
        // V3 check
            Integer hasV3Col = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'deletion_scheduled_at'",
                    Integer.class);
        // V4 check
            Integer hasV4Col = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'contacts' AND column_name = 'is_friend'",
                    Integer.class);
        // V5 check
            Integer hasV5BarrierCol = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'sync_events' AND column_name = 'barrier_xid'",
                    Integer.class);
            Integer hasV5Trigger = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_sync_events_set_barrier' AND NOT tgisinternal",
                    Integer.class);
            Boolean retentionRls = jdbc.queryForObject(
                    "SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = 'sync_retention_state'",
                    Boolean.class);
            Long initialCursor = jdbc.queryForObject(
                    "SELECT min_available_cursor FROM public.sync_retention_state WHERE id = 1",
                    Long.class);
        // V6 check
            Integer hasV6Cols = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                            + "AND ((table_name = 'calls' AND column_name IN ('caller_device_id_hash', 'callee_device_id_hash', 'expires_at', 'updated_at', 'caller_alive_until', 'callee_alive_until')) "
                            + "OR (table_name = 'attachments' AND column_name = 'upload_client_id'))",
                    Integer.class);
            Integer hasV6Indexes = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname IN "
                            + "('uq_calls_active_caller', 'uq_calls_active_callee', 'idx_calls_ringing_expires', 'uq_messages_call_id', 'uq_attachments_upload_client')",
                    Integer.class);
            Integer v6Rls = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('calls', 'messages', 'attachments') AND rowsecurity = true",
                    Integer.class);
            
            assertEquals(1, hasV3Col);
            assertEquals(1, hasV4Col);
            assertEquals(1, hasV5BarrierCol);
            assertEquals(1, hasV5Trigger);
            assertEquals(7, hasV6Cols);
            assertEquals(5, hasV6Indexes);
            assertEquals(Boolean.TRUE, retentionRls);
            assertEquals(3, v6Rls);
            assertEquals(0L, initialCursor);
        }
    }

    @Test
    void ignoresMissingDemoV2SeedWhenAppStartsWithProductionClasspathLocations() throws Exception {
        try (var database = new PostgresFixture()) {
            DataSource ds = database.source;
            // Mô phỏng database demo từng chạy Flyway V1 trước, kèm bản ghi V2 đã áp dụng từ CLI.
            Flyway cli = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .target("1")
                    .load();
            cli.migrate();

            // Giả lập lịch sử từng có V2 (ví dụ V2__seed_demo_data.sql) trong flyway_schema_history.
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) "
                            + "VALUES (2, '2', 'seed demo data', 'SQL', 'V2__seed_demo_data.sql', 12345, 'postgres', 5, true)");

            Flyway appFlyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .ignoreMigrationPatterns("*:missing")
                    .load();
            appFlyway.migrate();

            Integer v4Applied = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success = true",
                    Integer.class);
            assertEquals(1, v4Applied);
        }
    }
}
