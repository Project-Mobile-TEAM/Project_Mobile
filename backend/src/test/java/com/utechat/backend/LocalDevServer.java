package com.utechat.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Chạy backend thật trên PostgreSQL nhúng (schema V1) để thử app Android khi chưa có Supabase/.env.
 * Dữ liệu mất khi tắt. Chạy từ thư mục backend:
 *   mvnw spring-boot:run -Dspring-boot.run.main-class=com.utechat.backend.LocalDevServer -Dspring-boot.run.useTestClasspath=true
 * OTP demo hiện trên màn hình app và ghi vào backend/otp-local/.
 */
public class LocalDevServer {
    public static void main(String[] args) throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        try (Connection c = pg.getPostgresDatabase().getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("src/main/resources/db/migration/V1__initial_schema.sql"));
        }
        // Tham số dòng lệnh có độ ưu tiên cao hơn application.yml (default properties thì thấp hơn nên không ghi đè được).
        SpringApplication.run(BackendApplication.class,
                "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=",
                "--utechat.otp.local-dir=otp-local");
    }
}
