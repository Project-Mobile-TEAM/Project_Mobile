package com.utechat.backend.otp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kiểm tra OTP demo được ghi đúng file và từ chối số không hợp lệ. */
class LocalFileOtpDeliveryTest {
    @TempDir Path dir;

    @Test void writesLatestCodeToFilePerPhone() throws Exception {
        LocalFileOtpDelivery delivery = new LocalFileOtpDelivery(dir.resolve("otp-local").toString());
        delivery.send("+84912345678", "111111");
        delivery.send("+84912345678", "222222"); // Gửi lại thì ghi đè bằng mã mới.
        String content = Files.readString(dir.resolve("otp-local/84912345678.txt"));
        assertTrue(content.contains("code=222222"));
        assertFalse(content.contains("111111"));
    }

    @Test void rejectsNonE164ToBlockPathTraversal() {
        LocalFileOtpDelivery delivery = new LocalFileOtpDelivery(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> delivery.send("../../evil", "123456"));
        assertThrows(IllegalArgumentException.class, () -> delivery.send("0912345678", "123456"));
    }
}
