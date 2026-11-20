package com.utechat.backend.service;

import com.utechat.backend.dto.AuthDtos.DeviceDto;
import com.utechat.backend.dto.AuthDtos.UpdateProfileRequest;
import com.utechat.backend.dto.AuthDtos.UserDto;
import com.utechat.backend.exception.ApiException;
import com.utechat.backend.security.AuthenticatedActor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Hồ sơ, quyền riêng tư, đổi mật khẩu, thiết bị và xoá mềm tài khoản (F-05, F-08). Danh tính luôn lấy từ JWT. */
@Service
public class AccountService {
    private static final int MIN_PASSWORD = 8;
    public static final int DELETION_GRACE_DAYS = 15;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SyncEventWriter syncEvents;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final TransactionTemplate tx;

    public AccountService(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            SyncEventWriter syncEvents,
            ObjectProvider<ConversationService> conversationServiceProvider,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.syncEvents = syncEvents;
        this.conversationServiceProvider = conversationServiceProvider;
        this.tx = new TransactionTemplate(txManager);
    }

    public UserDto getProfile(UUID userId) {
        List<UserDto> rows = jdbc.query(
                "SELECT id, phone_e164, display_name, avatar_url, discoverable, allow_messages_from_non_contacts, "
                        + "deletion_scheduled_at FROM users WHERE id = ? AND status = 'ACTIVE'",
                (rs, i) -> {
                    Timestamp due = rs.getTimestamp(7);
                    return new UserDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getBoolean(5), rs.getBoolean(6), due == null ? null : due.toInstant().toString());
                }, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
        return rows.get(0);
    }

    /** Sửa tên, ảnh và hai cờ riêng tư; trường null giữ nguyên. Bảng users không có cột updated_at. */
    public UserDto updateProfile(UUID userId, UpdateProfileRequest req) {
        String name = req.displayName() == null ? null : req.displayName().trim();
        if (name != null && (name.isEmpty() || name.length() > 50)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "displayName must be 1 to 50 characters");
        }
        String avatar = req.avatarMediaUrl();
        // Ảnh chỉ được trỏ tới file đã upload qua POST /media (Phase 6), không nhận URL tuỳ ý.
        if (avatar != null && !avatar.matches("^/api/media/[0-9a-fA-F-]{36}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "avatarMediaUrl is invalid");
        }
        jdbc.update("UPDATE users SET display_name = COALESCE(?, display_name), avatar_url = COALESCE(?, avatar_url), "
                + "discoverable = COALESCE(?, discoverable), "
                + "allow_messages_from_non_contacts = COALESCE(?, allow_messages_from_non_contacts) WHERE id = ?",
                name, avatar, req.discoverable(), req.allowMessagesFromNonContacts(), userId);
        return getProfile(userId);
    }

    /**
     * Đổi mật khẩu rồi đăng xuất các thiết bị khác. Mật khẩu cũ sai trả 400 (không phải 401) để app không nhầm
     * với hết phiên và tự đăng xuất.
     */
    @Transactional
    public void changePassword(AuthenticatedActor actor, String oldPassword, String newPassword) {
        requirePassword(actor.userId(), oldPassword);
        requireStrong(newPassword);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(newPassword), actor.userId());
        jdbc.update("UPDATE user_devices SET token_valid_after = ? WHERE user_id = ? AND device_id_hash <> ?",
                now(), actor.userId(), actor.deviceIdHash());
    }

    public List<DeviceDto> getDevices(AuthenticatedActor actor) {
        return jdbc.query("SELECT id, device_name, platform, last_seen_at, device_id_hash FROM user_devices "
                        + "WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC",
                (rs, i) -> new DeviceDto(rs.getString("id"), rs.getString("device_name"), rs.getString("platform"),
                        rs.getTimestamp("last_seen_at").toInstant().toString(),
                        actor.deviceIdHash().equals(rs.getString("device_id_hash"))), actor.userId());
    }

    /** Thu hồi một thiết bị KHÁC (muốn thoát thiết bị này thì dùng đăng xuất); ghi sync_event qua SyncEventWriter. */
    @Transactional
    public void revokeDevice(AuthenticatedActor actor, UUID targetId) {
        int n = jdbc.update("UPDATE user_devices SET revoked_at = now(), token_valid_after = ? "
                + "WHERE id = ? AND user_id = ? AND device_id_hash <> ? AND revoked_at IS NULL",
                now(), targetId, actor.userId(), actor.deviceIdHash());
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found");
        syncEvents.write(actor.userId(), "DEVICE_REVOKED", "DEVICE", targetId.toString(), Map.of());
    }

    /**
     * Yêu cầu xoá tài khoản (F-08), giống Facebook: tài khoản chờ 15 ngày rồi mới bị xoá vĩnh viễn. Ngay bây giờ chỉ
     * đăng xuất mọi thiết bị; đăng nhập lại trong 15 ngày thì app hỏi có huỷ yêu cầu xoá không.
     */
    @Transactional
    public void scheduleDeletion(UUID userId, String password) {
        requirePassword(userId, password);
        jdbc.update("UPDATE users SET deletion_scheduled_at = now() + make_interval(days => ?) WHERE id = ?",
                DELETION_GRACE_DAYS, userId);
        jdbc.update("UPDATE user_devices SET revoked_at = now(), token_valid_after = ? WHERE user_id = ?", now(), userId);
    }

    /** Chủ tài khoản đăng nhập lại trong thời gian chờ và chọn giữ tài khoản. */
    public void cancelDeletion(UUID userId) {
        jdbc.update("UPDATE users SET deletion_scheduled_at = NULL WHERE id = ?", userId);
    }

    /**
     * Xoá vĩnh viễn các tài khoản đã hết 15 ngày chờ.
     * Mỗi user chạy trong một transaction ngắn riêng biệt (TransactionTemplate) để không giữ lâu pg_snapshot_xmin
     * làm nghẽn sync barrier của /api/sync.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpiredDeletions() {
        List<UUID> due = jdbc.query("SELECT id FROM users WHERE status = 'ACTIVE' AND deletion_scheduled_at <= now()",
                (rs, i) -> rs.getObject(1, UUID.class));
        for (UUID userId : due) {
            tx.executeWithoutResult(status -> purge(userId));
        }
    }

    /**
     * Xoá mềm: giải phóng SĐT, ẩn tên/ảnh, xoá quan hệ bạn bè/lời mời, thu hồi thiết bị,
     * tự động phong ADMIN kế nhiệm ở các nhóm còn người rồi mới rời mọi hội thoại.
     */
    private void purge(UUID userId) {
        // Tự động bàn giao ADMIN cho thành viên vào sớm nhất ở các nhóm userId là ADMIN duy nhất trước khi rời.
        ConversationService convService = conversationServiceProvider.getIfAvailable();
        if (convService != null) {
            convService.handleUserAccountPurge(userId);
        }

        jdbc.update("UPDATE users SET status = 'DELETED', phone_e164 = NULL, display_name = 'Tài khoản đã xóa', "
                + "avatar_url = NULL, deleted_at = now() WHERE id = ?", userId);
        jdbc.update("DELETE FROM contacts WHERE owner_id = ? OR contact_user_id = ?", userId, userId);
        jdbc.update("DELETE FROM contact_requests WHERE requester_id = ? OR addressee_id = ?", userId, userId);
        jdbc.update("UPDATE user_devices SET revoked_at = now(), token_valid_after = ? WHERE user_id = ?", now(), userId);
        jdbc.update("UPDATE conversation_members SET left_at = now() WHERE user_id = ? AND left_at IS NULL", userId);
    }

    /** Mốc thu hồi theo đồng hồ của server (cùng nguồn với iatMs của token). */
    private static Timestamp now() { return Timestamp.from(Instant.now()); }

    private void requirePassword(UUID userId, String password) {
        List<String> hash = jdbc.query("SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE'",
                (rs, i) -> rs.getString(1), userId);
        if (password == null || hash.isEmpty() || !encoder.matches(password, hash.get(0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CREDENTIALS", "Current password is incorrect");
        }
    }

    static void requireStrong(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "Password must be at least 8 characters");
        }
    }
}
