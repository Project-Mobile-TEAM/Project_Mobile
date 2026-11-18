package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.utechat.backend.service.ContactPolicyService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Chạy truy vấn chính sách bằng JDBC thật trên H2; không giả lập advisory lock PostgreSQL. */
class ContactPolicyServiceTest {
    private JdbcTemplate jdbc;
    private ContactPolicyService policy;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(source);
        policy = new ContactPolicyService(jdbc);
        // Bảng tối thiểu có cùng cột truy vấn, không thay cho kiểm thử migration PostgreSQL.
        jdbc.execute("CREATE TABLE contacts(owner_id UUID NOT NULL, contact_user_id UUID NOT NULL, "
                + "is_friend BOOLEAN NOT NULL, blocked BOOLEAN NOT NULL, PRIMARY KEY(owner_id,contact_user_id))");
    }

    @AfterEach void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    private void row(UUID owner, UUID other, boolean friend, boolean blocked) {
        jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,?,?)",
                owner, other, friend, blocked);
    }

    /** Không có quan hệ thì không phải bạn bè và không có quyết định chặn. */
    @Test void strangersHaveNoFriendshipOrBlock() {
        assertFalse(policy.isBlockedBy(a, b));
        assertFalse(policy.isBlockedEitherWay(a, b));
        assertFalse(policy.areAcceptedFriends(a, b));
    }

    /** Một dòng chặn chỉ thuộc A nhưng đủ để cản cả A và B tương tác. */
    @Test void singleBlockHasOneOwnerButBlocksBothDirections() {
        row(a, b, false, true);
        assertTrue(policy.isBlockedBy(a, b));
        assertFalse(policy.isBlockedBy(b, a));
        assertTrue(policy.isBlockedEitherWay(a, b));
        assertTrue(policy.isBlockedEitherWay(b, a));
        assertFalse(policy.areAcceptedFriends(a, b));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contacts", Integer.class));
    }

    /** Quyết định chặn người thứ ba không ảnh hưởng cặp đang kiểm tra. */
    @Test void unrelatedBlockDoesNotAffectFriends() {
        row(a, b, true, false);
        row(b, a, true, false);
        row(c, a, false, true);
        assertFalse(policy.isBlockedEitherWay(a, b));
        assertFalse(policy.isBlockedBy(a, b));
        assertTrue(policy.areAcceptedFriends(a, b));
    }

    /** Dữ liệu chỉ có một chiều bạn bè chưa đủ điều kiện để cho phép tương tác giữa bạn bè. */
    @Test void oneFriendRowIsInsufficient() {
        row(a, b, true, false);
        assertFalse(policy.areAcceptedFriends(a, b));
        assertFalse(policy.areAcceptedFriends(b, a));
    }

    /** Hai chiều bạn bè không chặn phải được nhận diện như nhau dù đổi thứ tự UUID. */
    @Test void reciprocalFriendsAreAcceptedInEitherOrder() {
        row(a, b, true, false);
        row(b, a, true, false);
        assertTrue(policy.areAcceptedFriends(a, b));
        assertTrue(policy.areAcceptedFriends(b, a));
    }

    /** is_friend vẫn giữ nhưng một chiều blocked đã đủ cấm tương tác bạn bè. */
    @Test void blockingFriendDisablesFriendInteractionWithoutClearingFlag() {
        row(a, b, true, true);
        row(b, a, true, false);
        assertFalse(policy.areAcceptedFriends(a, b));
        assertFalse(policy.areAcceptedFriends(b, a));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM contacts WHERE is_friend", Integer.class));
    }

    /** Hai bạn bè cùng chặn nhau vẫn giữ cờ bạn bè nhưng chưa được phép tương tác. */
    @Test void mutualBlocksPreserveFriendFlagsButDenyInteraction() {
        row(a, b, true, true);
        row(b, a, true, true);
        assertTrue(policy.isBlockedBy(a, b));
        assertTrue(policy.isBlockedBy(b, a));
        assertFalse(policy.areAcceptedFriends(a, b));
        assertFalse(policy.areAcceptedFriends(b, a));
    }

    /** Chỉ kiểm guard trước JDBC; không gọi hàm advisory lock không được H2 hỗ trợ. */
    @Test void pairLockRejectsMissingTransactionBeforeQueryingDatabase() {
        var error = assertThrows(IllegalStateException.class, () -> policy.lockPair(a, b));
        assertEquals("Pair lock requires transaction", error.getMessage());
    }
}
