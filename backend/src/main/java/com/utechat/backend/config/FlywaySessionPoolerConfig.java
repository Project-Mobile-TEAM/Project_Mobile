package com.utechat.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bảo đảm Flyway luôn chạy qua kết nối Session Pooler / Direct (cổng 5432) thay vì Transaction Pooler (cổng 6543),
 * vì Transaction Pooler không giữ phiên xuyên giao dịch cho advisory lock của Flyway.
 */
@Configuration
public class FlywaySessionPoolerConfig {

    /**
     * Nếu thành viên quên cấu hình FLYWAY_URL trong .env và URL dự phòng đang trỏ cổng 6543,
     * tự động chuyển cổng sang 5432 và bỏ prepareThreshold=0 dành riêng cho Transaction Pooler.
     */
    @Bean
    FlywayConfigurationCustomizer flywaySessionPoolerCustomizer(
            @Value("${spring.flyway.url:}") String flywayUrl,
            @Value("${spring.flyway.user:}") String flywayUser,
            @Value("${spring.flyway.password:}") String flywayPassword) {
        return configuration -> {
            if (flywayUrl != null && flywayUrl.contains(":6543/")) {
                String sessionUrl = toSessionPoolerUrl(flywayUrl);
                configuration.dataSource(sessionUrl, flywayUser, flywayPassword);
            }
        };
    }

    /** Đổi URL Transaction Pooler 6543 sang Session Pooler 5432 mà vẫn giữ nguyên host, tên DB và tham số TLS. */
    static String toSessionPoolerUrl(String url) {
        return url.replace(":6543/", ":5432/")
                .replace("&prepareThreshold=0", "")
                .replace("prepareThreshold=0&", "")
                .replace("?prepareThreshold=0", "");
    }
}
