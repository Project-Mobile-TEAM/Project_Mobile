package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.config.TimeConfig;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.service.LookupRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;

/** Kiểm thời gian bằng Clock giả và kiểm đồng thời thật, không dùng Thread.sleep. */
class LookupRateLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LookupRateLimiter limiter = new LookupRateLimiter(clock);
    private final UUID user = UUID.randomUUID();

    private static class MutableClock extends Clock {
        private long timeMillis;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return Instant.ofEpochMilli(timeMillis); }
    }

    /** Ngưỡng áp dụng từ lượt 11 và mang đủ mã HTTP, mã lỗi cùng thời gian chờ. */
    @Test void allowsTenThenRejectsEleventhWithRetryAfter() {
        fillWindow();
        ApiException error = assertThrows(ApiException.class, () -> limiter.checkAndRecord(user));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("RATE_LIMITED", error.getCode());
        assertEquals(60L, error.getDetails().get("retryAfterSeconds"));
    }

    /** Một người dùng hết lượt không làm mất lượt của người khác. */
    @Test void isolatesEachUser() {
        fillWindow();
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> limiter.checkAndRecord(other));
        assertThrows(ApiException.class, () -> limiter.checkAndRecord(other));
    }

    /** Mốc đúng 60 giây phải giải phóng lượt, không buộc chờ thêm một giây. */
    @Test void releasesSlotsAtExactWindowBoundary() {
        fillWindow();
        clock.timeMillis = 59_999;
        assertThrows(ApiException.class, () -> limiter.checkAndRecord(user));
        clock.timeMillis = 60_000;
        fillWindow();
        assertThrows(ApiException.class, () -> limiter.checkAndRecord(user));
    }

    /** Cửa sổ trượt chỉ bỏ lượt hết hạn, không reset tất cả theo phút lịch. */
    @Test void preservesUnexpiredSlotsWhenFirstRequestExpires() {
        limiter.checkAndRecord(user);
        clock.timeMillis = 30_000;
        for (int i = 0; i < 9; i++) limiter.checkAndRecord(user);
        clock.timeMillis = 60_000;
        assertDoesNotThrow(() -> limiter.checkAndRecord(user));
        ApiException error = assertThrows(ApiException.class, () -> limiter.checkAndRecord(user));
        assertEquals(30L, error.getDetails().get("retryAfterSeconds"));
    }

    /** Phần lẻ một mili giây vẫn cần chờ một giây khi trả thông tin cho client. */
    @Test void roundsRetryAfterUp() {
        fillWindow();
        clock.timeMillis = 1;
        assertEquals(60L, rejected().getDetails().get("retryAfterSeconds"));
        clock.timeMillis = 59_001;
        assertEquals(1L, rejected().getDetails().get("retryAfterSeconds"));
    }

    /** Gửi tiếp khi đang bị giới hạn không gia hạn khóa. */
    @Test void rejectedRequestsDoNotExtendWindow() {
        fillWindow();
        clock.timeMillis = 30_000;
        for (int i = 0; i < 20; i++) rejected();
        clock.timeMillis = 60_000;
        assertDoesNotThrow(() -> limiter.checkAndRecord(user));
    }

    /** Hai mươi luồng cùng tài khoản chỉ có đúng mười lượt được ghi nhận. */
    @Test void concurrentRequestsCannotExceedLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    limiter.checkAndRecord(user);
                    return true;
                } catch (ApiException error) {
                    assertEquals("RATE_LIMITED", error.getCode());
                    return false;
                }
            }));
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertEquals(10, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Kiểm chứng Spring tạo được service bằng Clock bean, không chỉ gọi constructor thủ công. */
    @Test void springWiresUtcClockAndSingletonLimiter() {
        try (var context = new AnnotationConfigApplicationContext(TimeConfig.class, LookupRateLimiter.class)) {
            assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
            assertSame(context.getBean(LookupRateLimiter.class), context.getBean(LookupRateLimiter.class));
        }
    }

    private void fillWindow() {
        for (int i = 0; i < 10; i++) limiter.checkAndRecord(user);
    }

    private ApiException rejected() {
        return assertThrows(ApiException.class, () -> limiter.checkAndRecord(user));
    }
}
