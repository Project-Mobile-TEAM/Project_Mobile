package com.utechat.backend.service;

import com.utechat.backend.dto.CallDtos.AcceptCallResponse;
import com.utechat.backend.dto.CallDtos.CallUpdatedEventPayload;
import com.utechat.backend.dto.CallDtos.CallerInfoDto;
import com.utechat.backend.dto.CallDtos.StartCallRequest;
import com.utechat.backend.dto.CallDtos.StartCallResponse;
import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nghiệp vụ vòng đời cuộc gọi thoại WebRTC 1-1 an toàn đồng thời (D-10, API_CONTRACT mục 2.6):
 * - Kiểm tra chính sách theo thứ tự: 400 CALL_UNSUPPORTED (GROUP/non-VOICE) -> 403 BLOCKED -> 403 CONTACT_REQUIRED -> 429 RATE_LIMITED -> 409 CALL_BUSY.
 * - Tuần tự hóa bằng ContactPolicyService.lockPair + khóa hàng public.users theo thứ tự UUID tăng dần + partial unique index V6.
 * - Chuyển trạng thái nguyên tử dưới khóa SELECT ... FOR UPDATE trên public.calls; phát call.handled_elsewhere tới thiết bị khác của người nhận.
 * - Quét tự động hết hạn đổ chuông 45s (MISSED/TIMEOUT) và hết hạn liveness 60s khi ACTIVE (FAILED/OFFLINE).
 * - Ghi tối đa ĐÚNG 1 tin nhắn loại CALL khi kết thúc nhờ partial unique index uq_messages_call_id + ON CONFLICT DO NOTHING.
 */
@Service
public class CallService {
    public static final long RINGING_TIMEOUT_SECONDS = 45L;
    public static final long ACTIVE_LIVENESS_SECONDS = 60L;

    private static final Set<String> TERMINAL_STATES =
            Set.of("ENDED", "MISSED", "REJECTED", "BUSY", "FAILED");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ContactPolicyService policy;
    private final CallRateLimiter rateLimiter;
    private final TurnCredentialsService turnCredentials;
    private final SyncEventWriter syncEvents;

    public CallService(
            JdbcTemplate jdbc,
            Clock clock,
            TransactionTemplate transactionTemplate,
            ContactPolicyService policy,
            CallRateLimiter rateLimiter,
            TurnCredentialsService turnCredentials,
            SyncEventWriter syncEvents) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.turnCredentials = turnCredentials;
        this.syncEvents = syncEvents;
    }

    /**
     * Khởi tạo cuộc gọi thoại 1-1 mới ở trạng thái RINGING (hạn đổ chuông 45s):
     * từ chối 409 CALL_BUSY nguyên tử mà không tạo bản ghi calls hay đẩy call.incoming nếu một trong hai bên đang bận.
     */
    @Transactional
    public StartCallResponse startCall(UUID callerId, String callerDeviceHash, StartCallRequest req) {
        if (req == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Call request is required");
        }
        UUID conversationId = ConversationService.parseRequiredUuid(req.conversationId(), "Invalid conversationId");
        String media = (req.media() == null || req.media().isBlank()) ? "VOICE" : req.media().trim();
        if (!"VOICE".equals(media)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALL_UNSUPPORTED", "Only VOICE calls are supported");
        }

        List<String> convTypes = jdbc.query(
                "SELECT type FROM public.conversations WHERE id = ?",
                (rs, i) -> rs.getString("type"),
                conversationId);
        if (convTypes.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        if ("GROUP".equals(convTypes.get(0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALL_UNSUPPORTED", "Group calls are not supported");
        }

        Boolean isActiveMember = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.conversation_members "
                        + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL)",
                Boolean.class,
                conversationId, callerId);
        if (!Boolean.TRUE.equals(isActiveMember)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }

        List<UUID> peers = jdbc.query(
                "SELECT user_id FROM public.conversation_members WHERE conversation_id = ? AND user_id <> ?",
                (rs, i) -> rs.getObject("user_id", UUID.class),
                conversationId, callerId);
        if (peers.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Callee not found");
        }
        UUID peerId = peers.get(0);

        // Thứ tự khóa chống deadlock: luôn lockPair trước, sau đó khóa 2 dòng users theo thứ tự UUID tăng dần.
        policy.lockPair(callerId, peerId);
        List<UserRow> lockedUsers = jdbc.query(
                "SELECT id, display_name, avatar_url, status FROM public.users WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE",
                (rs, i) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getString("status")),
                callerId, peerId);
        Map<UUID, UserRow> userMap = new HashMap<>();
        for (UserRow u : lockedUsers) {
            userMap.put(u.id(), u);
        }
        UserRow callerRow = userMap.get(callerId);
        UserRow peerRow = userMap.get(peerId);
        if (callerRow == null || !"ACTIVE".equals(callerRow.status())
                || peerRow == null || !"ACTIVE".equals(peerRow.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
        }

        // Chính sách D-10: kiểm tra 403 BLOCKED nghiêm ngặt trước 403 CONTACT_REQUIRED (bắt buộc là bạn bè ACCEPTED).
        if (policy.isBlockedEitherWay(callerId, peerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "User is blocked");
        }
        if (!policy.areAcceptedFriends(callerId, peerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CONTACT_REQUIRED", "Calls require accepted friendship");
        }

        // Giới hạn tốc độ 5 cuộc gọi / 60 giây / userId.
        rateLimiter.checkAndRecord(callerId);

        // Kiểm tra bận (D-10): nếu caller hoặc callee đang có cuộc gọi RINGING hoặc ACTIVE -> trả 409 CALL_BUSY, không tạo row.
        Boolean busy = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.calls "
                        + "WHERE state IN ('RINGING', 'ACTIVE') "
                        + "  AND (caller_id IN (?, ?) OR callee_id IN (?, ?)))",
                Boolean.class,
                callerId, peerId, callerId, peerId);
        if (Boolean.TRUE.equals(busy)) {
            throw new ApiException(HttpStatus.CONFLICT, "CALL_BUSY", "User is busy on another call");
        }

        Instant startedAt = nowMicros();
        Instant expiresAt = startedAt.plusSeconds(RINGING_TIMEOUT_SECONDS);
        UUID callId = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO public.calls "
                            + "(id, conversation_id, caller_id, callee_id, caller_device_id_hash, media, state, started_at, expires_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'VOICE', 'RINGING', ?, ?, ?)",
                    callId, conversationId, callerId, peerId, callerDeviceHash,
                    Timestamp.from(startedAt), Timestamp.from(expiresAt), Timestamp.from(startedAt));
        } catch (DataIntegrityViolationException e) {
            // Backstop từ partial unique index uq_calls_active_caller / uq_calls_active_callee.
            throw new ApiException(HttpStatus.CONFLICT, "CALL_BUSY", "User is busy on another call");
        }

        CallerInfoDto callerInfo = new CallerInfoDto(callerId.toString(), callerRow.displayName(), callerRow.avatarUrl());
        CallUpdatedEventPayload incomingPayload = new CallUpdatedEventPayload(
                callId.toString(),
                conversationId.toString(),
                "RINGING",
                null,
                "VOICE",
                "call.incoming",
                callerInfo,
                null,
                expiresAt.toString());
        syncEvents.write(peerId, "CALL_UPDATED", "CALL", callId.toString(), incomingPayload);

        return new StartCallResponse(
                callId.toString(),
                "RINGING",
                expiresAt.toString(),
                turnCredentials.generateIceServers(callerId));
    }

    /**
     * Người nhận chấp nhận cuộc gọi nguyên tử (RINGING -> ACTIVE):
     * khóa cố định callee_device_id_hash, khởi tạo liveness lease 60s cho hai bên,
     * phát call.accepted cho người gọi và call.handled_elsewhere cho các thiết bị khác của người nhận.
     */
    @Transactional
    public AcceptCallResponse acceptCall(UUID actorId, String actorDeviceHash, UUID callId) {
        CallRow call = lockCallOrThrow(callId);
        if (!actorId.equals(call.calleeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the callee can accept this call");
        }

        // Gọi lại từ đúng thiết bị đã nhận máy -> trả 200 OK idempotent, không ghi thêm sync_events.
        if ("ACTIVE".equals(call.state()) && Objects.equals(actorDeviceHash, call.calleeDeviceIdHash())) {
            return new AcceptCallResponse(
                    callId.toString(),
                    "ACTIVE",
                    turnCredentials.generateIceServers(actorId));
        }

        Instant now = nowMicros();
        if (!"RINGING".equals(call.state()) || !now.isBefore(call.expiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Call is no longer active for accept");
        }

        Instant aliveUntil = now.plusSeconds(ACTIVE_LIVENESS_SECONDS);
        jdbc.update(
                "UPDATE public.calls "
                        + "SET state = 'ACTIVE', callee_device_id_hash = ?, answered_at = ?, updated_at = ?, "
                        + "    caller_alive_until = ?, callee_alive_until = ? "
                        + "WHERE id = ?",
                actorDeviceHash, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(aliveUntil), Timestamp.from(aliveUntil), callId);

        CallUpdatedEventPayload acceptedPayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                "ACTIVE",
                null,
                call.media(),
                "call.accepted",
                null,
                null,
                null);
        syncEvents.write(call.callerId(), "CALL_UPDATED", "CALL", callId.toString(), acceptedPayload);

        CallUpdatedEventPayload handledElsewherePayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                "ACTIVE",
                null,
                call.media(),
                "call.handled_elsewhere",
                null,
                actorDeviceHash,
                null);
        syncEvents.write(call.calleeId(), "CALL_UPDATED", "CALL", callId.toString(), handledElsewherePayload);

        return new AcceptCallResponse(
                callId.toString(),
                "ACTIVE",
                turnCredentials.generateIceServers(actorId));
    }

    /**
     * Người nhận từ chối cuộc gọi đang đổ chuông (RINGING -> REJECTED/REJECT):
     * phát call.ended cho người gọi, call.handled_elsewhere cho các thiết bị khác của người nhận và chèn 1 tin CALL.
     */
    @Transactional
    public void rejectCall(UUID actorId, String actorDeviceHash, UUID callId) {
        CallRow call = lockCallOrThrow(callId);
        if (!actorId.equals(call.calleeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the callee can reject this call");
        }

        if ("REJECTED".equals(call.state())) {
            // Idempotent replay: không ghi thêm sự kiện hay tin nhắn.
            return;
        }

        Instant now = nowMicros();
        if (!"RINGING".equals(call.state()) || !now.isBefore(call.expiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Call cannot be rejected in current state");
        }

        jdbc.update(
                "UPDATE public.calls SET state = 'REJECTED', end_reason = 'REJECT', ended_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), callId);

        CallUpdatedEventPayload endedPayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                "REJECTED",
                "REJECT",
                call.media(),
                "call.ended",
                null,
                null,
                null);
        syncEvents.write(call.callerId(), "CALL_UPDATED", "CALL", callId.toString(), endedPayload);

        CallUpdatedEventPayload handledElsewherePayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                "REJECTED",
                "REJECT",
                call.media(),
                "call.handled_elsewhere",
                null,
                actorDeviceHash,
                null);
        syncEvents.write(call.calleeId(), "CALL_UPDATED", "CALL", callId.toString(), handledElsewherePayload);

        insertTerminalCallMessageOnce(callId, call.conversationId(), call.callerId(), call.calleeId());
    }

    /**
     * Kết thúc hoặc hủy cuộc gọi (RINGING/ACTIVE -> ENDED/HANGUP):
     * - Đã ở trạng thái kết thúc -> trả 204 idempotent.
     * - Khi RINGING: chỉ thiết bị khởi tạo cuộc gọi của caller được phép hủy và chỉ trước expires_at (quá hạn -> 409 INVALID_STATE).
     * - Khi ACTIVE: chỉ đúng 2 thiết bị đang tham gia cuộc gọi được phép kết thúc.
     */
    @Transactional
    public void endCall(UUID actorId, String actorDeviceHash, UUID callId) {
        CallRow call = lockCallOrThrow(callId);
        boolean isCaller = actorId.equals(call.callerId());
        boolean isCallee = actorId.equals(call.calleeId());
        if (!isCaller && !isCallee) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not a participant of this call");
        }

        if (TERMINAL_STATES.contains(call.state())) {
            // Đã kết thúc trước đó -> idempotent, không chèn thêm tin nhắn hay phát lại sự kiện.
            return;
        }

        Instant now = nowMicros();
        if ("RINGING".equals(call.state())) {
            if (!isCaller || !Objects.equals(actorDeviceHash, call.callerDeviceIdHash())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the initiating caller device can cancel a ringing call");
            }
            if (!now.isBefore(call.expiresAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Call ringing window has expired");
            }
        } else if ("ACTIVE".equals(call.state())) {
            boolean isCallerDevice = isCaller && Objects.equals(actorDeviceHash, call.callerDeviceIdHash());
            boolean isCalleeDevice = isCallee && Objects.equals(actorDeviceHash, call.calleeDeviceIdHash());
            if (!isCallerDevice && !isCalleeDevice) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only participating call devices can end an active call");
            }
        }

        jdbc.update(
                "UPDATE public.calls SET state = 'ENDED', end_reason = 'HANGUP', ended_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), callId);

        CallUpdatedEventPayload endedPayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                "ENDED",
                "HANGUP",
                call.media(),
                "call.ended",
                null,
                null,
                null);
        syncEvents.write(call.callerId(), "CALL_UPDATED", "CALL", callId.toString(), endedPayload);
        syncEvents.write(call.calleeId(), "CALL_UPDATED", "CALL", callId.toString(), endedPayload);

        insertTerminalCallMessageOnce(callId, call.conversationId(), call.callerId(), call.calleeId());
    }

    /**
     * Tác vụ quét định kỳ mỗi 5 giây (public để test tiêm MutableClock gọi trực tiếp không cần sleep 45s):
     * - Chuyển RINGING quá hạn 45s -> MISSED/TIMEOUT.
     * - Chuyển ACTIVE có một bên hết hạn liveness 60s -> FAILED/OFFLINE.
     * Mỗi cuộc gọi chạy trong một TransactionTemplate độc lập với FOR UPDATE SKIP LOCKED.
     */
    @Scheduled(fixedDelay = 5_000)
    public void sweepExpiredCalls() {
        Timestamp nowTs = Timestamp.from(nowMicros());
        List<UUID> dueCallIds = jdbc.query(
                "SELECT id FROM ("
                        + "  (SELECT id, expires_at AS due_at FROM public.calls "
                        + "   WHERE state = 'RINGING' AND expires_at <= ? ORDER BY expires_at ASC LIMIT 50) "
                        + "  UNION ALL "
                        + "  (SELECT id, LEAST(COALESCE(caller_alive_until, TIMESTAMPTZ 'epoch'), "
                        + "                    COALESCE(callee_alive_until, TIMESTAMPTZ 'epoch')) AS due_at "
                        + "   FROM public.calls "
                        + "   WHERE state = 'ACTIVE' "
                        + "     AND (caller_alive_until IS NULL OR callee_alive_until IS NULL "
                        + "          OR caller_alive_until <= ? OR callee_alive_until <= ?) "
                        + "   ORDER BY due_at ASC LIMIT 50) "
                        + ") due ORDER BY due_at ASC LIMIT 50",
                (rs, i) -> rs.getObject("id", UUID.class),
                nowTs, nowTs, nowTs);

        for (UUID callId : dueCallIds) {
            transactionTemplate.executeWithoutResult(status -> expireSingleCall(callId));
        }
    }

    /** Hết hạn an toàn một cuộc gọi cụ thể dưới khóa FOR UPDATE SKIP LOCKED trong transaction riêng. */
    private void expireSingleCall(UUID callId) {
        List<CallRow> rows = jdbc.query(
                SELECT_CALL_ROW + " WHERE id = ? FOR UPDATE SKIP LOCKED",
                CallService::mapCallRow,
                callId);
        if (rows.isEmpty()) {
            return;
        }
        CallRow call = rows.get(0);
        Instant now = nowMicros();

        String newState;
        String endReason;
        if ("RINGING".equals(call.state()) && !now.isBefore(call.expiresAt())) {
            newState = "MISSED";
            endReason = "TIMEOUT";
        } else if ("ACTIVE".equals(call.state())
                && (call.callerAliveUntil() == null
                        || call.calleeAliveUntil() == null
                        || !now.isBefore(call.callerAliveUntil())
                        || !now.isBefore(call.calleeAliveUntil()))) {
            newState = "FAILED";
            endReason = "OFFLINE";
        } else {
            return;
        }

        jdbc.update(
                "UPDATE public.calls SET state = ?, end_reason = ?, ended_at = ?, updated_at = ? WHERE id = ?",
                newState, endReason, Timestamp.from(now), Timestamp.from(now), callId);

        CallerInfoDto callerInfo = null;
        if ("MISSED".equals(newState)) {
            callerInfo = loadCallerInfo(call.callerId());
        }

        CallUpdatedEventPayload endedPayload = new CallUpdatedEventPayload(
                callId.toString(),
                call.conversationId().toString(),
                newState,
                endReason,
                call.media(),
                "call.ended",
                callerInfo,
                null,
                null);
        syncEvents.write(call.callerId(), "CALL_UPDATED", "CALL", callId.toString(), endedPayload);
        syncEvents.write(call.calleeId(), "CALL_UPDATED", "CALL", callId.toString(), endedPayload);

        insertTerminalCallMessageOnce(callId, call.conversationId(), call.callerId(), call.calleeId());
    }

    private CallerInfoDto loadCallerInfo(UUID callerId) {
        List<CallerInfoDto> rows = jdbc.query(
                "SELECT display_name, avatar_url FROM public.users WHERE id = ?",
                (rs, i) -> new CallerInfoDto(
                        callerId.toString(),
                        rs.getString("display_name"),
                        rs.getString("avatar_url")),
                callerId);
        return rows.isEmpty() ? new CallerInfoDto(callerId.toString(), null, null) : rows.get(0);
    }

    /**
     * Gia hạn liveness deadline (now + 60s) cho đúng thiết bị gửi call.keepalive qua WebSocket:
     * chạy dưới khóa FOR UPDATE, yêu cầu ACTIVE + đúng thiết bị tham gia + cả hai deadline hiện tại đều chưa hết hạn.
     * Không ghi sync_events.
     */
    @Transactional
    public boolean renewActiveCallLease(UUID callId, UUID actorId, String actorDeviceHash) {
        List<CallRow> rows = jdbc.query(
                SELECT_CALL_ROW + " WHERE id = ? FOR UPDATE",
                CallService::mapCallRow,
                callId);
        if (rows.isEmpty()) {
            return false;
        }
        CallRow call = rows.get(0);
        Instant now = nowMicros();
        if (!"ACTIVE".equals(call.state())) {
            return false;
        }
        if (call.callerAliveUntil() == null || call.calleeAliveUntil() == null
                || !now.isBefore(call.callerAliveUntil()) || !now.isBefore(call.calleeAliveUntil())) {
            return false;
        }

        Timestamp newDeadline = Timestamp.from(now.plusSeconds(ACTIVE_LIVENESS_SECONDS));
        Timestamp nowTs = Timestamp.from(now);
        if (actorId.equals(call.callerId()) && Objects.equals(actorDeviceHash, call.callerDeviceIdHash())) {
            jdbc.update(
                    "UPDATE public.calls SET caller_alive_until = ?, updated_at = ? WHERE id = ?",
                    newDeadline, nowTs, callId);
            return true;
        }
        if (actorId.equals(call.calleeId()) && Objects.equals(actorDeviceHash, call.calleeDeviceIdHash())) {
            jdbc.update(
                    "UPDATE public.calls SET callee_alive_until = ?, updated_at = ? WHERE id = ?",
                    newDeadline, nowTs, callId);
            return true;
        }
        return false;
    }

    /** Tra cứu thông tin 2 thiết bị và liveness lease cho ChatWebSocketHandler trước khi chuyển tiếp offer/answer/ice. */
    @Transactional(readOnly = true)
    public CallSignalingInfo findActiveCallForSignaling(UUID callId) {
        List<CallSignalingInfo> rows = jdbc.query(
                "SELECT conversation_id, caller_id, callee_id, caller_device_id_hash, callee_device_id_hash, "
                        + "state, caller_alive_until, callee_alive_until "
                        + "FROM public.calls WHERE id = ?",
                (rs, i) -> new CallSignalingInfo(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("caller_id", UUID.class),
                        rs.getObject("callee_id", UUID.class),
                        rs.getString("caller_device_id_hash"),
                        rs.getString("callee_device_id_hash"),
                        rs.getString("state"),
                        toInstant(rs.getTimestamp("caller_alive_until")),
                        toInstant(rs.getTimestamp("callee_alive_until"))),
                callId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Chèn ĐÚNG 1 tin nhắn loại CALL vào bảng public.messages khi cuộc gọi kết thúc:
     * sử dụng partial unique index uq_messages_call_id với ON CONFLICT (call_id) WHERE call_id IS NOT NULL DO NOTHING
     * để đảm bảo không bao giờ phát sinh tin nhắn CALL trùng lặp ngay cả khi end/reject/timeout xảy ra đồng thời.
     */
    private void insertTerminalCallMessageOnce(UUID callId, UUID conversationId, UUID callerId, UUID calleeId) {
        List<Long> insertedIds = jdbc.query(
                "INSERT INTO public.messages (conversation_id, sender_id, client_id, type, call_id) "
                        + "VALUES (?, ?, ?, 'CALL', ?) "
                        + "ON CONFLICT (call_id) WHERE call_id IS NOT NULL DO NOTHING "
                        + "RETURNING id",
                (rs, i) -> rs.getLong("id"),
                conversationId, callerId, callId, callId);
        if (insertedIds.isEmpty()) {
            return;
        }
        long newMsgId = insertedIds.get(0);
        jdbc.update(
                "UPDATE public.conversations SET last_message_id = GREATEST(COALESCE(last_message_id, 0), ?) WHERE id = ?",
                newMsgId, conversationId);

        MessageDto dto = jdbc.queryForObject(
                MessageService.SELECT_MESSAGE_WITH_METADATA + " WHERE m.id = ?",
                MessageService::mapMessageRow,
                newMsgId);
        syncEvents.write(callerId, "MESSAGE_CREATED", "MESSAGE", String.valueOf(newMsgId), dto);
        syncEvents.write(calleeId, "MESSAGE_CREATED", "MESSAGE", String.valueOf(newMsgId), dto);
    }

    private CallRow lockCallOrThrow(UUID callId) {
        List<CallRow> rows = jdbc.query(
                SELECT_CALL_ROW + " WHERE id = ? FOR UPDATE",
                CallService::mapCallRow,
                callId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND", "Call not found");
        }
        return rows.get(0);
    }

    private static final String SELECT_CALL_ROW =
            "SELECT id, conversation_id, caller_id, callee_id, caller_device_id_hash, callee_device_id_hash, "
                    + "media, state, end_reason, expires_at, caller_alive_until, callee_alive_until "
                    + "FROM public.calls";

    private static CallRow mapCallRow(ResultSet rs, int rowNum) throws SQLException {
        return new CallRow(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("caller_id", UUID.class),
                rs.getObject("callee_id", UUID.class),
                rs.getString("caller_device_id_hash"),
                rs.getString("callee_device_id_hash"),
                rs.getString("media"),
                rs.getString("state"),
                rs.getString("end_reason"),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("caller_alive_until")),
                toInstant(rs.getTimestamp("callee_alive_until")));
    }

    private Instant nowMicros() {
        // Cắt về micro giây để khớp chính xác độ phân giải TIMESTAMPTZ của PostgreSQL, tránh làm tròn lên vài trăm nano giây.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** Dữ liệu cuộc gọi phục vụ kiểm tra khóa báo hiệu 2 thiết bị và liveness lease trong ChatWebSocketHandler. */
    public record CallSignalingInfo(
            UUID conversationId,
            UUID callerId,
            UUID calleeId,
            String callerDeviceIdHash,
            String calleeDeviceIdHash,
            String state,
            Instant callerAliveUntil,
            Instant calleeAliveUntil) {
        /** Kiểm tra cuộc gọi đang ACTIVE và cả hai bên đều còn trong hạn liveness lease 60s. */
        public boolean isActiveWithLiveLeases(Instant now) {
            return "ACTIVE".equals(state)
                    && callerAliveUntil != null
                    && calleeAliveUntil != null
                    && now.isBefore(callerAliveUntil)
                    && now.isBefore(calleeAliveUntil);
        }
    }

    private record UserRow(UUID id, String displayName, String avatarUrl, String status) {
    }

    private record CallRow(
            UUID id,
            UUID conversationId,
            UUID callerId,
            UUID calleeId,
            String callerDeviceIdHash,
            String calleeDeviceIdHash,
            String media,
            String state,
            String endReason,
            Instant expiresAt,
            Instant callerAliveUntil,
            Instant calleeAliveUntil) {
    }
}
