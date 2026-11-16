package com.utechat.backend.service;

import com.utechat.backend.exception.ApiException;
import com.utechat.backend.util.HashUtils;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Chống dò mật khẩu bằng bảng auth_throttles (ADR-003). Hai tầng khoá:
 * PHONE_DEVICE (5 lần sai khoá 15 phút, riêng cặp số+thiết bị) và PHONE (20 lần sai trong 1 giờ khoá 1 giờ, mọi thiết bị).
 * Mỗi câu lệnh tự commit (service này không chạy trong transaction của caller) nên số lần sai vẫn được ghi
 * kể cả khi caller ném lỗi 401 ngay sau đó.
 */
@Service
public class ThrottleService {
    private static final int DEVICE_THRESHOLD = 5, DEVICE_WINDOW_SEC = 15 * 60, DEVICE_LOCK_SEC = 15 * 60;
    private static final int PHONE_THRESHOLD = 20, PHONE_WINDOW_SEC = 60 * 60, PHONE_LOCK_SEC = 60 * 60;
    private final JdbcTemplate jdbc;

    public ThrottleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ném 423 LOCKED (kèm retryAfterSeconds) nếu cặp số+thiết bị hoặc cả số đang bị khoá. */
    public void check(String purpose, String phone, String deviceHash) {
        List<Long> remaining = jdbc.query(
                "SELECT CEIL(EXTRACT(EPOCH FROM (locked_until - now())))::bigint FROM auth_throttles "
                        + "WHERE purpose = ? AND locked_until > now() AND "
                        + "((scope_type = 'PHONE_DEVICE' AND scope_key_hash = ?) OR (scope_type = 'PHONE' AND scope_key_hash = ?))",
                (rs, i) -> rs.getLong(1), purpose, deviceKey(phone, deviceHash), phoneKey(phone));
        if (!remaining.isEmpty()) {
            long retry = remaining.stream().mapToLong(Long::longValue).max().getAsLong();
            throw new ApiException(HttpStatus.LOCKED, "LOCKED", "Too many attempts",
                    Map.of("retryAfterSeconds", retry));
        }
    }

    /** Ghi một lần sai ở cả hai tầng; đủ ngưỡng thì khoá. */
    public void fail(String purpose, String phone, String deviceHash) {
        record(purpose, "PHONE_DEVICE", deviceKey(phone, deviceHash), DEVICE_THRESHOLD, DEVICE_WINDOW_SEC, DEVICE_LOCK_SEC);
        record(purpose, "PHONE", phoneKey(phone), PHONE_THRESHOLD, PHONE_WINDOW_SEC, PHONE_LOCK_SEC);
    }

    /** Đăng nhập đúng: xoá bộ đếm của cả hai tầng. */
    public void reset(String purpose, String phone, String deviceHash) {
        jdbc.update("UPDATE auth_throttles SET failure_count = 0, locked_until = NULL, updated_at = now() "
                + "WHERE purpose = ? AND ((scope_type = 'PHONE_DEVICE' AND scope_key_hash = ?) OR (scope_type = 'PHONE' AND scope_key_hash = ?))",
                purpose, deviceKey(phone, deviceHash), phoneKey(phone));
    }

    private void record(String purpose, String scopeType, String key, int threshold, int windowSec, int lockSec) {
        // Một câu upsert: ngoài cửa sổ thì đếm lại từ 1, trong cửa sổ thì cộng thêm 1.
        Integer count = jdbc.queryForObject(
                "INSERT INTO auth_throttles (scope_type, scope_key_hash, purpose, failure_count, window_started_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, now(), now()) "
                        + "ON CONFLICT (scope_type, scope_key_hash, purpose) DO UPDATE SET "
                        + "failure_count = CASE WHEN auth_throttles.window_started_at < now() - make_interval(secs => ?) "
                        + "THEN 1 ELSE auth_throttles.failure_count + 1 END, "
                        + "window_started_at = CASE WHEN auth_throttles.window_started_at < now() - make_interval(secs => ?) "
                        + "THEN now() ELSE auth_throttles.window_started_at END, updated_at = now() "
                        + "RETURNING failure_count",
                Integer.class, scopeType, key, purpose, windowSec, windowSec);
        if (count != null && count >= threshold) {
            // Khoá và đếm lại từ đầu để hết hạn khoá là được thử lại với đủ số lần.
            jdbc.update("UPDATE auth_throttles SET locked_until = now() + make_interval(secs => ?), failure_count = 0, "
                    + "window_started_at = now() WHERE scope_type = ? AND scope_key_hash = ? AND purpose = ?",
                    lockSec, scopeType, key, purpose);
        }
    }

    private static String phoneKey(String phone) { return HashUtils.sha256Hex(phone); }
    private static String deviceKey(String phone, String deviceHash) { return HashUtils.sha256Hex(phone + ":" + deviceHash); }
}
