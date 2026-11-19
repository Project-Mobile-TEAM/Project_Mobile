package com.utechat.backend.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Quy tắc quan hệ dùng chung cho danh bạ, nhắn tin và gọi thoại. */
@Service
public class ContactPolicyService {
    private final JdbcTemplate jdbc;
    public ContactPolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Khóa theo cặp không phân biệt chiều; mọi thao tác phải lấy khóa này trước khóa dòng. */
    public void lockPair(UUID a, UUID b) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Pair lock requires transaction");
        UUID lo = a.compareTo(b) < 0 ? a : b, hi = a.compareTo(b) < 0 ? b : a;
        try {
            long key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(
                    ("utechat-contact-pair:" + lo + ":" + hi).getBytes(StandardCharsets.UTF_8))).getLong();
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, key);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Chỉ cần một bên chặn là cả hai bên không được tương tác theo chính sách. */
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contacts WHERE "
                + "((owner_id=? AND contact_user_id=?) OR (owner_id=? AND contact_user_id=?)) AND blocked)", Boolean.class, a,b,b,a));
    }

    /** Quyết định chặn thuộc về chủ dòng; không tự suy ra đối phương cũng chặn ngược lại. */
    public boolean isBlockedBy(UUID blocker, UUID target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contacts "
                + "WHERE owner_id=? AND contact_user_id=? AND blocked)", Boolean.class, blocker, target));
    }

    /** Hai dòng is_friend là điều kiện cần; bất kỳ chiều chặn nào cũng cấm tương tác bạn bè. */
    public boolean areAcceptedFriends(UUID a, UUID b) {
        // Đọc bằng một câu SQL để không ghép kết quả trước và sau một thay đổi chặn đồng thời.
        // PK(owner_id, contact_user_id) bảo đảm cặp khác nhau chỉ có tối đa hai dòng.
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*) = 2 FROM contacts WHERE "
                + "((owner_id=? AND contact_user_id=?) OR (owner_id=? AND contact_user_id=?)) "
                + "AND is_friend AND NOT blocked", Boolean.class, a, b, b, a));
    }
}
