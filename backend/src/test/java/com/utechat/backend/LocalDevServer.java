package com.utechat.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/**
 * Chạy backend thật trên PostgreSQL nhúng (Flyway tự chạy V1 + V3 + V4, không seed V2) để thử app khi chưa có Supabase/.env.
 * Dữ liệu mất khi tắt. Chạy từ thư mục backend:
 *   mvnw spring-boot:run -Dspring-boot.run.main-class=com.utechat.backend.LocalDevServer -Dspring-boot.run.useTestClasspath=true
 * OTP demo hiện trên màn hình app và ghi vào backend/otp-local/.
 */
public class LocalDevServer {
    public static void main(String[] args) throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        SpringApplication.run(BackendApplication.class, databaseArguments(pg.getJdbcUrl("postgres", "postgres"), "postgres", ""));
    }

    /** Buộc cả runtime và Flyway vào database test; FLYWAY_URL từ môi trường không được trỏ sang Supabase. */
    static String[] databaseArguments(String url, String user, String password) {
        return new String[] {
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password,
                "--spring.flyway.url=" + url, "--spring.flyway.user=" + user,
                "--spring.flyway.password=" + password, "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration", "--utechat.otp.local-dir=otp-local"
        };
    }
}
