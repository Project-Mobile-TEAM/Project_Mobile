package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.ContactDtos.ContactRequestDto;
import com.utechat.backend.dto.ContactDtos.UserSummaryDto;
import com.utechat.backend.service.ContactPolicyService;
import com.utechat.backend.service.SyncEventWriter;
import com.utechat.backend.support.PostgresFixture;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL, JSONB, Spring proxy và hai kết nối đồng thời trên PostgreSQL thật; không dùng H2. */
@Timeout(20)
class ContactPostgresFoundationTest {
    @TestConfiguration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }
    private PostgresFixture db;
    private AnnotationConfigApplicationContext context;
    private ContactPolicyService policy;
    private SyncEventWriter writer;
    private TransactionTemplate tx;
    private UUID a, b, c, d;

    @BeforeEach void setup() throws Exception {
        db = new PostgresFixture();
        db.migrate();
        a = user(); b = user(); c = user(); d = user();
        db.jdbc.execute("CREATE TABLE business_marker(id INT PRIMARY KEY)");
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(db.source));
        context.registerBean(JdbcTemplate.class, () -> db.jdbc);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(SyncEventWriter.class);
        context.refresh();
        policy = new ContactPolicyService(db.jdbc);
        writer = context.getBean(SyncEventWriter.class);
        tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setTimeout(10);
    }

    @AfterEach void close() throws Exception {
        if (context != null) context.close();
        if (db != null) db.close();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO users(id,display_name,password_hash) VALUES (?,'Test','test')", id);
        return id;
    }

    private void row(UUID owner, UUID other, boolean friend, boolean blocked) {
        db.jdbc.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,?,?)", owner, other, friend, blocked);
    }

    private int events() { return db.jdbc.queryForObject("SELECT count(*) FROM sync_events", Integer.class); }
    private int markers() { return db.jdbc.queryForObject("SELECT count(*) FROM business_marker", Integer.class); }

    /** Guard phải chạy trước khi lấy advisory lock ngoài transaction. */
    @Test void lockRequiresTransaction() {
        assertThrows(IllegalStateException.class, () -> policy.lockPair(a, b));
    }

    /** Cặp đảo chiều phải chờ đúng cùng một lock; commit xong mới được đi tiếp. */
    @Test void reversePairWaitsAndCommitReleasesLock() throws Exception { waitingPair(false); }

    /** Rollback cũng nhả transaction lock, không để kết nối giữ khóa đến khi đóng session. */
    @Test void reversePairWaitsAndRollbackReleasesLock() throws Exception { waitingPair(true); }

    private void waitingPair(boolean rollback) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        AtomicReference<Future<String>> waiter = new AtomicReference<>();
        try {
            tx.executeWithoutResult(status -> {
                policy.lockPair(a, b);
                // Khóa dòng chỉ được lấy sau khóa cặp, cùng thứ tự ở cả hai transaction.
                db.jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, a);
                waiter.set(pool.submit(() -> tx.execute(second -> {
                    pid.set(db.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    started.countDown();
                    policy.lockPair(b, a);
                    db.jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, a);
                    return "locked";
                })));
                assertTrue(assertDoesNotThrow(() -> started.await(3, TimeUnit.SECONDS)));
                // Quan sát wait_event tại PostgreSQL để không nhầm thread chưa chạy với thread đang chờ khóa.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    db.jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(db.jdbc.queryForObject(
                            "SELECT wait_event_type='Lock' AND wait_event='advisory' FROM pg_stat_activity WHERE pid=?", Boolean.class, pid.get()));
                    if (waiting) break;
                    assertDoesNotThrow(() -> Thread.sleep(10));
                }
                assertTrue(waiting, "Kết nối thứ hai phải thực sự chờ advisory lock tại server");
                assertFalse(waiter.get().isDone());
                if (rollback) status.setRollbackOnly();
            });
            assertEquals("locked", waiter.get().get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Cặp độc lập không bị serialize bởi một khóa toàn hệ thống. */
    @Test void unrelatedPairCanProceedWhileFirstPairIsLocked() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                policy.lockPair(a, b);
                Future<Boolean> other = pool.submit(() -> tx.execute(second -> {
                    policy.lockPair(c, d);
                    return true;
                }));
                assertTrue(assertDoesNotThrow(() -> other.get(3, TimeUnit.SECONDS)));
            });
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Kiểm predicate thật trên UUID/boolean PostgreSQL; block một chiều cấm tương tác cả hai bên. */
    @Test void relationshipPredicatesRequireTwoUnblockedFriendRows() {
        assertFalse(policy.areAcceptedFriends(a, b));
        row(a, b, true, false);
        assertFalse(policy.areAcceptedFriends(a, b));
        row(b, a, true, false);
        assertTrue(policy.areAcceptedFriends(b, a));
        db.jdbc.update("UPDATE contacts SET blocked=TRUE WHERE owner_id=? AND contact_user_id=?", a, b);
        assertTrue(policy.isBlockedBy(a, b));
        assertFalse(policy.isBlockedBy(b, a));
        assertTrue(policy.isBlockedEitherWay(b, a));
        assertFalse(policy.areAcceptedFriends(a, b));
        assertEquals(2, db.jdbc.queryForObject("SELECT count(*) FROM contacts WHERE is_friend", Integer.class));
    }

    /** MANDATORY được thực thi bởi Spring proxy trên đúng datasource PostgreSQL. */
    @Test void eventWriterRejectsMissingTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> writer.write(a, "CONTACT_UPDATED", "CONTACT", b.toString(), Map.of("removed", true)));
        assertEquals(0, events());
    }

    /** Hai người nhận và thay đổi nghiệp vụ cùng commit; JSONB giữ cấu trúc Unicode và hồ sơ tối thiểu. */
    @Test void jsonbRequestEventsCommitForBothRecipientsWithBusinessChange() {
        String request = UUID.randomUUID().toString();
        var payload = new ContactRequestDto(request, new UserSummaryDto(a.toString(), "Người gửi", null),
                new UserSummaryDto(b.toString(), "Người nhận", null), "PENDING", "2026-11-18T00:00:00Z", null);
        tx.executeWithoutResult(status -> {
            db.jdbc.update("INSERT INTO business_marker VALUES (1)");
            writer.write(a, "CONTACT_REQUEST_CREATED", "CONTACT_REQUEST", request, payload);
            writer.write(b, "CONTACT_REQUEST_CREATED", "CONTACT_REQUEST", request, payload);
        });
        assertEquals(1, markers());
        assertEquals(2, events());
        assertEquals(2, db.jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM sync_events", Integer.class));
        assertEquals(2, db.jdbc.queryForObject("SELECT count(*) FROM sync_events WHERE pg_typeof(payload_json)::text='jsonb' AND payload_json->'requester'->>'displayName'='Người gửi' AND NOT (payload_json->'requester' ? 'phone') AND NOT (payload_json->'addressee' ? 'phone')", Integer.class));
    }

    /** Hợp đồng UPDATED và CONTACT_UPDATED cũng phải qua CHECK enum/JSONB thật. */
    @Test void updateEventsUsePostgresContractAndPayloadTypes() {
        var request = new ContactRequestDto(UUID.randomUUID().toString(), new UserSummaryDto(a.toString(), "A", null),
                new UserSummaryDto(b.toString(), "B", null), "REJECTED", "2026-11-18T00:00:00Z", "2026-11-18T01:00:00Z");
        tx.executeWithoutResult(status -> {
            writer.write(a, "CONTACT_REQUEST_UPDATED", "CONTACT_REQUEST", request.id(), request);
            writer.write(a, "CONTACT_UPDATED", "CONTACT", b.toString(),
                    Map.of("contactUserId", b.toString(), "isFriend", false, "blocked", true, "removed", false));
        });
        assertEquals("boolean", db.jdbc.queryForObject("SELECT jsonb_typeof(payload_json->'blocked') FROM sync_events WHERE event_type='CONTACT_UPDATED'", String.class));
        assertEquals("REJECTED", db.jdbc.queryForObject("SELECT payload_json->>'status' FROM sync_events WHERE event_type='CONTACT_REQUEST_UPDATED'", String.class));
    }

    /** Rollback caller không để lại cả marker lẫn sự kiện cho bất kỳ người nhận nào. */
    @Test void callerRollbackRemovesBusinessChangeAndBothEvents() {
        tx.executeWithoutResult(status -> {
            db.jdbc.update("INSERT INTO business_marker VALUES (1)");
            writer.write(a, "CONTACT_UPDATED", "CONTACT", b.toString(), Map.of("removed", true));
            writer.write(b, "CONTACT_UPDATED", "CONTACT", a.toString(), Map.of("removed", true));
            status.setRollbackOnly();
        });
        assertEquals(0, markers());
        assertEquals(0, events());
    }

    /** Lỗi CHECK hoặc FK ở sự kiện thứ hai phải rollback cả lần ghi đầu và nghiệp vụ. */
    @Test void databaseFailureRollsBackEarlierWrites() {
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            db.jdbc.update("INSERT INTO business_marker VALUES (1)");
            writer.write(a, "CONTACT_UPDATED", "CONTACT", b.toString(), Map.of("removed", true));
            writer.write(UUID.randomUUID(), "CONTACT_UPDATED", "CONTACT", a.toString(), Map.of("removed", true));
        }));
        assertEquals(0, markers());
        assertEquals(0, events());
    }

    /** Enum sự kiện trong schema thật phải từ chối giá trị lạ và rollback nghiệp vụ. */
    @Test void invalidEventTypeRollsBackBusinessChange() {
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            db.jdbc.update("INSERT INTO business_marker VALUES (1)");
            writer.write(a, "UNKNOWN_EVENT", "CONTACT", b.toString(), Map.of("removed", true));
        }));
        assertEquals(0, markers());
        assertEquals(0, events());
    }

    /** Lỗi serialize không bị nuốt; kể cả caller bắt lỗi, Spring vẫn ngăn commit transaction. */
    @Test void caughtSerializationFailureStillRollsBackCaller() {
        assertThrows(UnexpectedRollbackException.class, () -> tx.executeWithoutResult(status -> {
            db.jdbc.update("INSERT INTO business_marker VALUES (1)");
            assertThrows(IllegalStateException.class,
                    () -> writer.write(a, "CONTACT_UPDATED", "CONTACT", b.toString(), new Object()));
        }));
        assertEquals(0, markers());
        assertEquals(0, events());
    }
}
