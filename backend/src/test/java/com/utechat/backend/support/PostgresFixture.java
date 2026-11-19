package com.utechat.backend.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Mặc định tự chạy PostgreSQL nhúng; mỗi fixture có database riêng, không đọc .env hoặc dùng Supabase. */
public final class PostgresFixture implements AutoCloseable {
    private static EmbeddedPostgres embedded;
    private final DriverManagerDataSource admin;
    private final String name = "phase4_" + UUID.randomUUID().toString().replace("-", "");
    public final DriverManagerDataSource source;
    public final JdbcTemplate jdbc;

    /** Dùng chung engine trong một JVM, không dùng chung dữ liệu; Zonky tự dọn engine khi JVM kết thúc. */
    private static synchronized EmbeddedPostgres embedded() throws IOException {
        if (embedded == null) embedded = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start();
        return embedded;
    }

    public PostgresFixture() throws Exception {
        String url = System.getProperty("phase4.pg.url");
        boolean useEmbedded = url == null;
        String password;
        if (useEmbedded) {
            // Không cần script, Docker hoặc mật khẩu môi trường; engine chọn cổng trống trên loopback.
            url = "jdbc:postgresql://127.0.0.1:" + embedded().getPort() + "/postgres";
            password = "";
        } else {
            // Giữ hỗ trợ PostgreSQL test được cấp tường minh; không rơi về môi trường nếu URL sai.
            password = System.getenv("PHASE4_TEST_PG_PASSWORD");
        }
        // Chặn cấu hình nhầm URL từ .env; chỉ tạo/xóa database ngẫu nhiên trên cổng localhost chỉ định.
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Test yêu cầu URL PostgreSQL JDBC");
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (!"127.0.0.1".equals(uri.getHost())
                || uri.getPort() <= 0 || !"/postgres".equals(uri.getPath()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Test chỉ nhận jdbc:postgresql://127.0.0.1:<port>/postgres");
        }
        if (password == null || (!useEmbedded && password.isEmpty()))
            throw new IllegalStateException("Thiếu mật khẩu PostgreSQL test được cấp tường minh");
        admin = new DriverManagerDataSource(url, "postgres", password);
        admin.setDriverClassName("org.postgresql.Driver");
        source = new DriverManagerDataSource(url.replaceFirst("/postgres(?=\\?|$)", "/" + name), "postgres", password);
        // Đặt timeout bằng Properties để lệnh Maven không phải mang query URL có dấu & qua cmd.exe.
        var options = new Properties();
        options.setProperty("connectTimeout", "5");
        options.setProperty("socketTimeout", "15");
        admin.setConnectionProperties(options);
        source.setConnectionProperties(options);
        jdbc = new JdbcTemplate(source);
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
    }

    /** Dùng Flyway và locations giống application.yml; target phục vụ fixture nâng cấp từ V1. */
    public Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").ignoreMigrationPatterns("*:missing");
        if (target != null) config.target(target);
        return config.load();
    }

    public void migrate() { flyway(null).migrate(); }

    /** Chỉ xóa database do chính fixture vừa tạo; không dùng Flyway clean trên database dùng chung. */
    @Override public void close() throws Exception {
        if (!name.matches("phase4_[a-f0-9]{32}")) throw new IllegalStateException("Tên database test không hợp lệ");
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE " + name + " WITH (FORCE)");
        }
    }
}
