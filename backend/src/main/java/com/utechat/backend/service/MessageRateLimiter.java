package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Giới hạn tần suất gửi tin nhắn theo cửa sổ trượt (Sliding Window Log):
 * tối đa 30 tin nhắn trong 10 giây cho mỗi userId (API_CONTRACT mục 4).
 */
@Service
public class MessageRateLimiter {
    public static final int MAX_MESSAGES = 30;
    public static final long WINDOW_SECONDS = 10L;

    private final Clock clock;
    private final ConcurrentHashMap<UUID, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public MessageRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Kiểm tra và ghi nhận một lượt gửi tin mới; ném 429 RATE_LIMITED kèm retryAfterSeconds nếu vượt ngưỡng.
     */
    public void checkAndRecord(UUID userId) {
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        Deque<Instant> timestamps = buckets.computeIfAbsent(userId, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            // Loại bỏ các mốc thời gian đã nằm ngoài cửa sổ 10 giây gần nhất.
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_MESSAGES) {
                Instant oldest = timestamps.peekFirst();
                long remainingMs = Math.max(1L, Duration.between(now, oldest.plusSeconds(WINDOW_SECONDS)).toMillis());
                long retryAfterSeconds = Math.max(1L, (remainingMs + 999L) / 1000L);
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED",
                        "Too many messages",
                        Map.of("retryAfterSeconds", retryAfterSeconds));
            }
            timestamps.addLast(now);
        }

        // Dọn dẹp định kỳ các hàng đợi rỗng để tránh tích tụ bộ nhớ theo thời gian.
        if (buckets.size() > 1000) {
            pruneEmptyBuckets(cutoff);
        }
    }

    /** Xóa các bucket đã hết hạn toàn bộ mốc thời gian. */
    private void pruneEmptyBuckets(Instant cutoff) {
        buckets.entrySet().removeIf(entry -> {
            Deque<Instant> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }
}
