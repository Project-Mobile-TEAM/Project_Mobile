package com.utechat.backend.config;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.support.PostgresFixture;
import java.sql.Connection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Kiểm chứng cấu hình Flyway tự động: chuyển Transaction Pooler 6543 sang Session Pooler 5432,
 * baseline an toàn cho database từng nạp V1/V2 thủ công, và bỏ qua V2 seed khi chạy production.
 */
class FlywayMigrationTest {

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
    void migratesPreexistingManualDatabaseWithBaselineAndAppliesV3AndV4() throws Exception {
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
            Integer hasV3Col = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'deletion_scheduled_at'",
                    Integer.class);
            Integer hasV4Col = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'contacts' AND column_name = 'is_friend'",
                    Integer.class);
            assertEquals(1, hasV3Col);
            assertEquals(1, hasV4Col);
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
