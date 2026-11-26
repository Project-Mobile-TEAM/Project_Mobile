package com.utechat.backend.service;

import com.utechat.backend.util.HashUtils;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bước nhận diện bạn bè trên thiết bị lạ (F-04, ADR-005): 3 bạn thật + 12 mồi, trộn ngẫu nhiên; chọn đúng cả 3.
 * Chỉ áp dụng khi tài khoản có từ 3 bạn và hệ thống có đủ 12 mồi hợp lệ, ngược lại bỏ qua bước.
 * Mỗi bộ dùng một lần (UPDATE nguyên tử), nên không thử lại được cùng một bộ.
 */
@Service
public class FriendStepService {
    public static final int FRIENDS = 3, DECOYS = 12, MAX_ATTEMPTS = 3, TTL_SECONDS = 120, LOCK_SECONDS = 15 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public FriendStepService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Candidate(String id, String displayName) { }
    /** Một bộ 15 người đã chọn nhưng chưa lưu. */
    public record Picked(List<Candidate> candidates, String answerHash) { }
    public record Challenge(UUID id, List<Candidate> candidates, int attemptsLeft, boolean lastAttemptWrong) { }
    public enum Result { OK, WRONG, EXPIRED }
    public record Check(Result result, int attemptsUsed) { }

    /**
     * Chọn 3 bạn thật (còn hoạt động, không bị chặn) và 12 mồi (không phải bạn, không bị chặn, cho phép hiện hồ sơ).
     * null nếu tài khoản dưới 3 bạn hoặc không đủ 12 mồi: bước này bị bỏ qua.
     * shortcut: chưa ưu tiên bạn đã nhắn tin và chưa loại tài khoản bot (V1 chưa có bot); nâng cấp khi thêm bot.
     */
    public Picked pick(UUID userId) {
        List<Candidate> friends = jdbc.query("SELECT u.id, u.display_name FROM contacts c JOIN users u ON u.id = c.contact_user_id "
                + "WHERE c.owner_id = ? AND c.is_friend AND NOT c.blocked AND u.status = 'ACTIVE' ORDER BY random() LIMIT ?",
                (rs, i) -> new Candidate(rs.getString(1), rs.getString(2)), userId, FRIENDS);
        if (friends.size() < FRIENDS) return null;
        List<Candidate> decoys = jdbc.query("SELECT u.id, u.display_name FROM users u WHERE u.status = 'ACTIVE' AND u.discoverable "
                + "AND u.phone_e164 IS NOT NULL AND u.id <> ? AND NOT EXISTS "
                + "(SELECT 1 FROM contacts c WHERE c.owner_id = ? AND c.contact_user_id = u.id) ORDER BY random() LIMIT ?",
                (rs, i) -> new Candidate(rs.getString(1), rs.getString(2)), userId, userId, DECOYS);
        if (decoys.size() < DECOYS) return null;
        List<Candidate> all = new ArrayList<>(friends);
        all.addAll(decoys);
        Collections.shuffle(all, RANDOM);
        return new Picked(all, hash(friends.stream().map(Candidate::id).toList()));
    }

    /** Lưu bộ vừa chọn; attemptsUsed được chuyển tiếp từ bộ trước để đếm đủ 3 lần thử cho cả phiên. */
    public Challenge persist(UUID userId, UUID sessionId, Picked p, int attemptsUsed, boolean lastWrong) {
        UUID id = jdbc.queryForObject("INSERT INTO friend_challenges (user_id, auth_session_id, answer_hash, attempts_used, expires_at) "
                + "VALUES (?, ?, ?, ?, now() + make_interval(secs => ?)) RETURNING id",
                UUID.class, userId, sessionId, p.answerHash(), attemptsUsed, TTL_SECONDS);
        return new Challenge(id, p.candidates(), MAX_ATTEMPTS - attemptsUsed, lastWrong);
    }

    public int attemptsUsed(UUID sessionId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(attempts_used), 0) FROM friend_challenges WHERE auth_session_id = ?",
                Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    /** Tiêu thụ bộ rồi so đáp án. Bộ đã dùng, hết hạn hoặc không thuộc phiên này là EXPIRED (không tính lần thử). */
    public Check check(UUID sessionId, UUID challengeId, List<String> selected) {
        List<Object[]> row = jdbc.query("UPDATE friend_challenges SET consumed_at = now() WHERE id = ? AND auth_session_id = ? "
                + "AND consumed_at IS NULL AND expires_at > now() RETURNING answer_hash, attempts_used",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, challengeId, sessionId);
        if (row.isEmpty()) return new Check(Result.EXPIRED, 0);
        int used = (Integer) row.get(0)[1];
        return hash(selected).equals(row.get(0)[0]) ? new Check(Result.OK, used) : new Check(Result.WRONG, used + 1);
    }

    /** Số giây còn bị khoá bước bạn bè của tài khoản; 0 nếu không bị khoá. */
    public long lockedSeconds(UUID userId) {
        List<Long> r = jdbc.query("SELECT CEIL(EXTRACT(EPOCH FROM (friend_step_locked_until - now())))::bigint FROM users "
                + "WHERE id = ? AND friend_step_locked_until > now()", (rs, i) -> rs.getLong(1), userId);
        return r.isEmpty() ? 0 : r.get(0);
    }

    public void lock(UUID userId) {
        jdbc.update("UPDATE users SET friend_step_locked_until = now() + make_interval(secs => ?) WHERE id = ?", LOCK_SECONDS, userId);
    }

    private static String hash(List<String> ids) {
        return HashUtils.sha256Hex(ids.stream().map(String::toLowerCase).sorted().collect(Collectors.joining(",")));
    }
}
