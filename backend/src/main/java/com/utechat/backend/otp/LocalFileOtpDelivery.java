package com.utechat.backend.otp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * OTP demo không gửi SMS thật: ghi mã vào thư mục otp-local, mỗi số một file (otp-local/84912345678.txt).
 * Chỉ bật ở profile dev hoặc demo; prod sẽ dùng cổng SMS thật qua cùng interface OtpDelivery.
 */
@Component
@Profile({"dev", "demo"})
public class LocalFileOtpDelivery implements OtpDelivery {
    private final Path directory; // Thư mục chứa file OTP, mặc định tính từ nơi chạy backend.

    public LocalFileOtpDelivery(@Value("${utechat.otp.local-dir:otp-local}") String directory) {
        this.directory = Path.of(directory);
    }

    @Override public void send(String phoneE164, String code) {
        // Chỉ nhận E.164 chuẩn để tên file luôn là các chữ số, chống ghi ra ngoài thư mục (path traversal).
        if (!phoneE164.matches("\\+\\d{8,15}")) throw new IllegalArgumentException("Số điện thoại không hợp lệ");
        Path file = directory.resolve(phoneE164.substring(1) + ".txt");
        try {
            Files.createDirectories(directory);
            // Ghi đè: file luôn giữ mã mới nhất của số đó, kèm thời điểm gửi.
            Files.writeString(file, "code=" + code + "\nsentAt=" + Instant.now() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Không ghi được OTP demo", e);
        }
    }
}
