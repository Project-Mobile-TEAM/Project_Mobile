package com.utechat.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.AuthDtos.UserDto;
import com.utechat.backend.dto.MessagingDtos.BootstrapContactDto;
import com.utechat.backend.dto.MessagingDtos.BootstrapContactRequestDto;
import com.utechat.backend.dto.MessagingDtos.BootstrapResponse;
import com.utechat.backend.dto.MessagingDtos.BootstrapUserDto;
import com.utechat.backend.dto.MessagingDtos.ConversationDto;
import com.utechat.backend.dto.MessagingDtos.SyncEventDto;
import com.utechat.backend.dto.MessagingDtos.SyncResponse;
import com.utechat.backend.exception.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ đồng bộ dữ liệu an toàn không nhảy cóc (D-06, D-07):
 * - GET /api/sync/bootstrap chạy trong 1 transaction REPEATABLE READ READ ONLY trả snapshot + syncCursor nhất quán.
 * - GET /api/sync?after=&limit= lọc barrier_xid <= pg_snapshot_xmin(pg_current_snapshot()) để chống bỏ sót
 *   khi 2 transaction commit ngược thứ tự sync_events.id, trả retryAfterMs = 1000 khi còn sự kiện bị barrier giữ,
 *   và trả 410 SYNC_CURSOR_EXPIRED khi con trỏ nằm ngoài ngưỡng lưu trữ 30 ngày hoặc vượt sequence.
 */
@Service
public class SyncService {
    private static final long BARRIER_RETRY_AFTER_MS = 1000L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccountService accountService;
    private final ConversationService conversationService;

    public SyncService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AccountService accountService,
            ConversationService conversationService) {
        this.jdbc = jdbc;
        this.json = json;
        this.accountService = accountService;
        this.conversationService = conversationService;
    }

    /**
     * Khởi tạo snapshot nhất quán kèm syncCursor trong một transaction REPEATABLE READ READ ONLY.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public BootstrapResponse bootstrap(UUID actorId) {
        // Câu SQL đầu tiên chốt snapshot MVCC của PostgreSQL và lấy mốc syncCursor an toàn nhất quán với dữ liệu bên dưới.
        Long cursorObj = jdbc.queryForObject(
                "SELECT GREATEST("
                        + "COALESCE((SELECT MAX(id) FROM public.sync_events WHERE barrier_xid <= pg_snapshot_xmin(pg_current_snapshot())), 0), "
                        + "COALESCE((SELECT min_available_cursor FROM public.sync_retention_state WHERE id = 1), 0))",
                Long.class);
        long syncCursor = cursorObj == null ? 0L : cursorObj;

        UserDto me = accountService.getProfile(actorId);
        List<ConversationDto> conversations = conversationService.listConversations(actorId);

        // Danh sách bạn bè chính thức (is_friend = true, blocked = false, target ACTIVE) được trả phone_e164.
        List<BootstrapContactDto> friends = jdbc.query(
                "SELECT u.id, u.phone_e164, u.display_name, u.avatar_url, c.blocked, c.created_at "
                        + "FROM public.contacts c JOIN public.users u ON u.id = c.contact_user_id "
                        + "WHERE c.owner_id = ? AND c.is_friend AND NOT c.blocked AND u.status = 'ACTIVE' "
                        + "ORDER BY LOWER(u.display_name) ASC, u.id ASC",
                (rs, i) -> new BootstrapContactDto(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("phone_e164"),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getBoolean("blocked"),
                        toIso(rs.getTimestamp("created_at"))),
                actorId);

        // Danh sách bị chặn (blocked = true, target ACTIVE): chỉ trả phone_e164 nếu từng là bạn bè (is_friend = true).
        List<BootstrapContactDto> blocked = jdbc.query(
                "SELECT u.id, CASE WHEN c.is_friend THEN u.phone_e164 ELSE NULL END AS phone_e164, "
                        + "u.display_name, u.avatar_url, c.blocked, c.created_at "
                        + "FROM public.contacts c JOIN public.users u ON u.id = c.contact_user_id "
                        + "WHERE c.owner_id = ? AND c.blocked AND u.status = 'ACTIVE' "
                        + "ORDER BY c.created_at DESC, u.id ASC",
                (rs, i) -> new BootstrapContactDto(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("phone_e164"),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getBoolean("blocked"),
                        toIso(rs.getTimestamp("created_at"))),
                actorId);

        // Lời mời kết bạn PENDING của cả hai chiều: dùng BootstrapUserDto KHÔNG lộ SĐT.
        List<BootstrapContactRequestDto> contactRequests = jdbc.query(
                "SELECT r.id, r.status, r.created_at, r.responded_at, "
                        + "a.id AS req_id, a.display_name AS req_name, a.avatar_url AS req_avatar, "
                        + "b.id AS add_id, b.display_name AS add_name, b.avatar_url AS add_avatar "
                        + "FROM public.contact_requests r "
                        + "JOIN public.users a ON a.id = r.requester_id "
                        + "JOIN public.users b ON b.id = r.addressee_id "
                        + "WHERE (r.requester_id = ? OR r.addressee_id = ?) "
                        + "AND r.status = 'PENDING' AND a.status = 'ACTIVE' AND b.status = 'ACTIVE' "
                        + "ORDER BY r.created_at DESC, r.id ASC",
                (rs, i) -> new BootstrapContactRequestDto(
                        rs.getObject("id", UUID.class).toString(),
                        new BootstrapUserDto(
                                rs.getObject("req_id", UUID.class).toString(),
                                rs.getString("req_name"),
                                rs.getString("req_avatar")),
                        new BootstrapUserDto(
                                rs.getObject("add_id", UUID.class).toString(),
                                rs.getString("add_name"),
                                rs.getString("add_avatar")),
                        rs.getString("status"),
                        toIso(rs.getTimestamp("created_at")),
                        toIso(rs.getTimestamp("responded_at"))),
                actorId, actorId);

        return new BootstrapResponse(me, conversations, friends, blocked, contactRequests, syncCursor);
    }

    /**
     * Đồng bộ gia tăng không nhảy cóc từ mốc after trong một snapshot REPEATABLE READ READ ONLY.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public SyncResponse sync(UUID actorId, long after, int requestedLimit) {
        if (after < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "after must be >= 0");
        }
        int limit = Math.max(1, Math.min(100, requestedLimit));

        // Chốt safeXmin một lần duy nhất trong snapshot REPEATABLE READ để đọc sự kiện và kiểm tra barrier nhất quán.
        String safeXmin = jdbc.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text", String.class);

        Long minAvailObj = jdbc.queryForObject(
                "SELECT min_available_cursor FROM public.sync_retention_state WHERE id = 1",
                Long.class);
        long minAvailableCursor = minAvailObj == null ? 0L : minAvailObj;

        SeqState seq = jdbc.queryForObject(
                "SELECT last_value, is_called FROM public.sync_events_id_seq",
                (rs, i) -> new SeqState(rs.getLong("last_value"), rs.getBoolean("is_called")));

        // Kiểm tra con trỏ hết hạn: cũ hơn ngưỡng dọn 30 ngày, hoặc vượt sequence (kể cả DB mới/reset chưa gọi sequence).
        if (after < minAvailableCursor
                || (!seq.isCalled && after > 0)
                || (seq.isCalled && after > seq.lastValue)) {
            throw new ApiException(
                    HttpStatus.GONE,
                    "SYNC_CURSOR_EXPIRED",
                    "Sync cursor expired",
                    Map.of("minAvailableCursor", minAvailableCursor));
        }

        // Lấy limit + 1 dòng để xác định chính xác hasMore mà không cần câu COUNT phụ.
        List<RawSyncEvent> rawRows = jdbc.query(
                "SELECT id, event_type, entity_type, entity_id, payload_json::text AS payload_text, created_at "
                        + "FROM public.sync_events "
                        + "WHERE user_id = ? AND id > ? AND barrier_xid <= CAST(? AS xid8) "
                        + "ORDER BY id ASC LIMIT ?",
                (rs, i) -> new RawSyncEvent(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("payload_text"),
                        toIso(rs.getTimestamp("created_at"))),
                actorId, after, safeXmin, limit + 1);

        boolean hasMore = rawRows.size() > limit;
        int pageSize = Math.min(rawRows.size(), limit);
        List<SyncEventDto> events = new ArrayList<>(pageSize);
        for (int i = 0; i < pageSize; i++) {
            RawSyncEvent row = rawRows.get(i);
            events.add(new SyncEventDto(
                    row.id,
                    row.eventType,
                    row.entityType,
                    row.entityId,
                    parsePayload(row.payloadText),
                    row.createdAt));
        }

        long nextCursor = events.isEmpty() ? after : events.get(events.size() - 1).id();

        // Trong cùng snapshot, kiểm tra xem còn sự kiện nào đã commit nhưng bị barrier_xid > safeXmin giữ lại không.
        Boolean hasBarrierHeld = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.sync_events WHERE user_id = ? AND id > ? AND barrier_xid > CAST(? AS xid8))",
                Boolean.class,
                actorId, nextCursor, safeXmin);
        Long retryAfterMs = Boolean.TRUE.equals(hasBarrierHeld) ? BARRIER_RETRY_AFTER_MS : null;

        return new SyncResponse(events, nextCursor, hasMore, retryAfterMs);
    }

    private JsonNode parsePayload(String payloadText) {
        try {
            return payloadText == null ? json.createObjectNode() : json.readTree(payloadText);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt sync_events payload_json", e);
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private record SeqState(long lastValue, boolean isCalled) {
    }

    private record RawSyncEvent(
            long id,
            String eventType,
            String entityType,
            String entityId,
            String payloadText,
            String createdAt) {
    }
}
