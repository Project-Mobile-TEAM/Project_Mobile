package com.utechat.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.support.PostgresFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Khởi động backend/Flyway và HTTP thật bằng đúng bộ tham số của LocalDevServer trên database cô lập. */
class LocalDevServerSmokeTest {
    @Test void devBootstrapMigratesItsOwnDatabaseAndReadinessUsesThatDatabase() throws Exception {
        try (var db = new PostgresFixture()) {
            var args = new ArrayList<>(Arrays.asList(LocalDevServer.databaseArguments(
                    db.source.getUrl(), db.source.getUsername(), db.source.getPassword())));
            // Cổng ngẫu nhiên và secret thử riêng; không đọc .env hay chiếm cổng 8080 của người dùng.
            args.addAll(Arrays.asList("--server.port=0", "--server.address=127.0.0.1", "--spring.profiles.active=dev",
                    "--jwt.secret=phase4-smoke-test-secret-not-a-production-key"));
            try (var context = SpringApplication.run(BackendApplication.class, args.toArray(String[]::new))) {
                var server = (ServletWebServerApplicationContext) context;
                var jdbc = context.getBean(JdbcTemplate.class);
                assertEquals(db.jdbc.queryForObject("SELECT current_database()", String.class),
                        jdbc.queryForObject("SELECT current_database()", String.class));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='4' AND success", Integer.class));
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getWebServer().getPort() + "/api/health/ready"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("CONNECTED", new ObjectMapper().readTree(response.body()).path("database").asText());
            }
        }
    }
}
