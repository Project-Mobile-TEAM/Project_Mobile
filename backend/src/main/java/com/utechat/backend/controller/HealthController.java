package com.utechat.backend.controller;

import com.utechat.backend.dto.HealthResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tách liveness của tiến trình và readiness của CSDL đúng API_CONTRACT mục 2.0. */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    // Constructor injection giúp thay JDBC bằng mock trong unit test.
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, String> health() {
        // Server còn sống ngay cả khi pooler hoặc CSDL đang mất kết nối.
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<HealthResponse> ready() {
        try {
            // Truy vấn thực tế qua HikariCP; không dùng cờ giả để báo CONNECTED.
            Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(result)) {
                return ResponseEntity.ok(response("UP", "CONNECTED"));
            }
            return ResponseEntity.status(503).body(response("DOWN", "UNEXPECTED_RESPONSE"));
        } catch (DataAccessException exception) {
            // Không trả exception message vì có thể chứa hostname, user hay credential.
            return ResponseEntity.status(503).body(response("DOWN", "DISCONNECTED"));
        }
    }

    private HealthResponse response(String status, String database) {
        // Dùng thời gian UTC ISO-8601 để mọi thiết bị hiểu cùng một mốc.
        return new HealthResponse(status, database, Instant.now().toString());
    }
}
