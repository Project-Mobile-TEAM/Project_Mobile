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
        // Tham số dòng lệnh có độ ưu tiên cao hơn application.yml (default properties thì thấp hơn nên không ghi đè được).
        SpringApplication.run(BackendApplication.class,
                "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=",
                "--utechat.otp.local-dir=otp-local");
    }
}
