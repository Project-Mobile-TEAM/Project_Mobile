package com.utechat.backend.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tác vụ dọn dẹp hằng ngày các sự kiện sync_events quá 30 ngày (ADR-008 / ERD),
 * đồng thời nâng sync_retention_state.min_available_cursor nguyên tử để /api/sync trả 410 SYNC_CURSOR_EXPIRED chính xác.
 */
@Service
public class SyncRetentionService {
    private static final Duration RETENTION_PERIOD = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SyncRetentionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Chạy lúc 03:00 mỗi ngày để xóa sự kiện cũ hơn 30 ngày. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeOldSyncEvents() {
        purgeOlderThan(Instant.now(clock).minus(RETENTION_PERIOD));
    }

    /**
     * Xóa các sự kiện đã cũ hơn mốc cutoff và đã qua barrier_xid <= xmin,
     * rồi nâng min_available_cursor trong cùng transaction.
     */
    @Transactional
    public long purgeOlderThan(Instant cutoff) {
        Long safeCutoffId = jdbc.queryForObject(
                "SELECT MAX(id) FROM public.sync_events "
                        + "WHERE created_at < ? AND barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())",
                Long.class,
                Timestamp.from(cutoff));
        if (safeCutoffId == null) {
            return 0L;
        }
        jdbc.update("DELETE FROM public.sync_events WHERE id <= ?", safeCutoffId);
        jdbc.update(
                "UPDATE public.sync_retention_state "
                        + "SET min_available_cursor = GREATEST(min_available_cursor, ?), updated_at = now() "
                        + "WHERE id = 1",
                safeCutoffId);
        return safeCutoffId;
    }
}
