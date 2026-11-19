package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Cửa sổ trượt: mỗi tài khoản có tối đa 10 lượt tra cứu được ghi nhận trong 60 giây. */
@Service
public class LookupRateLimiter {
    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MILLIS = 60_000;
    private final Clock clock;
    // shortcut: lưu trong bộ nhớ một instance V1; chuyển kho chung khi chạy nhiều instance.
    private final Map<UUID, Deque<Long>> attempts = new HashMap<>();

    public LookupRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Kiểm tra và ghi lượt dưới cùng một khóa để request đồng thời không vượt ngưỡng. */
    public synchronized void checkAndRecord(UUID userId) {
        long now = clock.millis();
        // Dọn lượt hết hạn của cả tài khoản không còn hoạt động, tránh giữ bucket cũ mãi mãi.
        attempts.values().forEach(queue -> {
            while (!queue.isEmpty() && queue.peekFirst() <= now - WINDOW_MILLIS) queue.removeFirst();
        });
        attempts.values().removeIf(Deque::isEmpty);

        Deque<Long> queue = attempts.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        if (queue.size() >= MAX_REQUESTS) {
            // Làm tròn lên để app không thử lại sớm hơn lúc lượt cũ hết hạn.
            long retryAfterSeconds = Math.max(1, (queue.peekFirst() + WINDOW_MILLIS - now + 999) / 1000);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many phone lookups",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        }
        // Lượt bị từ chối không được ghi vào hàng đợi, nên không kéo dài thời gian chờ.
        queue.addLast(now);
    }
}
