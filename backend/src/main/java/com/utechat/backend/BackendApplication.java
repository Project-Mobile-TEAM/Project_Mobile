package com.utechat.backend;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Điểm vào của backend; nạp cấu hình cục bộ trước khi Spring tạo DataSource. */
@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) {
        // Chạy từ root repo hoặc backend/ đều tìm thấy cùng một .env, không in giá trị.
        String directory = Files.isRegularFile(Path.of(".env")) ? "." : "..";
        Dotenv dotenv = Dotenv.configure().directory(directory).ignoreIfMissing().load();
        Map<String, Object> defaults = new HashMap<>();
        dotenv.entries().forEach(entry -> defaults.put(entry.getKey(), entry.getValue()));
        // Default properties có độ ưu tiên thấp hơn biến hệ điều hành và tham số dòng lệnh.
        SpringApplication application = new SpringApplication(BackendApplication.class);
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
