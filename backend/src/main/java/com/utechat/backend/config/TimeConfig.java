package com.utechat.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dùng giờ UTC thật khi chạy ứng dụng, thay đồng hồ giả khi kiểm thử thời gian chờ. */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
