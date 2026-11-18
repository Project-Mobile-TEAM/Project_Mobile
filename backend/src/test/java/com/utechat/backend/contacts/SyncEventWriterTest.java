package com.utechat.backend.contacts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.service.SyncEventWriter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC thật trên H2 kiểm ranh giới transaction của helper, không kiểm schema hoặc JSONB PostgreSQL. */
class SyncEventWriterTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SyncEventWriter writer;
    private TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final UUID recipient = UUID.randomUUID();

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfig { }

    @BeforeEach void setup() {
        // Giữ database giữa các kết nối JDBC, đóng rõ ràng bằng SHUTDOWN trong teardown.
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(SyncEventWriter.class);
        context.refresh();
        writer = context.getBean(SyncEventWriter.class);
        tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        // JSONB là VARCHAR trong fixture để chỉ kiểm chuỗi JSON và atomicity, không giả lập PostgreSQL JSONB.
        jdbc.execute("CREATE DOMAIN JSONB AS CHARACTER VARYING");
        jdbc.execute("CREATE TABLE business_marker(id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sync_events(user_id UUID NOT NULL, event_type VARCHAR(50) NOT NULL, "
                + "entity_type VARCHAR(50) NOT NULL, entity_id VARCHAR(100) NOT NULL, payload_json JSONB NOT NULL)");
    }

    @AfterEach void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (context != null) context.close();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void write(Object payload) {
        writer.write(recipient, "CONTACT_UPDATED", "CONTACT", "target", payload);
    }

    /** Phải gọi qua Spring proxy; thiếu transaction bị từ chối trước khi có dòng nào được ghi. */
    @Test void rejectsWriteWithoutExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> write(Map.of("blocked", true)));
        assertEquals(0, count("sync_events"));
    }

    /** Sự kiện và marker nghiệp vụ cùng commit, các giá trị được bind đúng cột. */
    @Test void commitsEventWithCallerAndPreservesPayload() throws Exception {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_marker VALUES (1)");
            write(Map.of("displayName", "Tên có dấu và 'dấu nháy'", "blocked", true));
        });
        assertEquals(1, count("business_marker"));
        var row = jdbc.queryForMap("SELECT * FROM sync_events");
        assertEquals(recipient, row.get("USER_ID"));
        assertEquals("CONTACT_UPDATED", row.get("EVENT_TYPE"));
        assertEquals("CONTACT", row.get("ENTITY_TYPE"));
        assertEquals("target", row.get("ENTITY_ID"));
        var payload = json.readTree((String) row.get("PAYLOAD_JSON"));
        assertEquals("Tên có dấu và 'dấu nháy'", payload.path("displayName").asText());
        assertTrue(payload.path("blocked").asBoolean());
    }

    /** Hai sự kiện nối tiếp không được tự commit khi transaction gọi quyết định rollback. */
    @Test void callerRollbackRemovesAllEventsAndBusinessChanges() {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_marker VALUES (1)");
            write(Map.of("blocked", true));
            write(Map.of("blocked", false));
            assertEquals(2, count("sync_events"));
            status.setRollbackOnly();
        });
        assertEquals(0, count("business_marker"));
        assertEquals(0, count("sync_events"));
    }

    /** Lỗi Jackson phải thoát ra và rollback thay đổi nghiệp vụ trước đó. */
    @Test void serializationFailureRollsBackCaller() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_marker VALUES (1)");
            write(new Object()); // Object rỗng không có serializer với cấu hình Jackson mặc định.
        }));
        assertEquals(0, count("business_marker"));
        assertEquals(0, count("sync_events"));
    }

    /** Kể cả caller bắt lỗi serialize, transaction đã rollback-only qua Spring proxy. */
    @Test void caughtSerializationFailureStillPreventsCommit() {
        assertThrows(UnexpectedRollbackException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_marker VALUES (1)");
            assertThrows(IllegalStateException.class, () -> write(new Object()));
        }));
        assertEquals(0, count("business_marker"));
    }

    /** Lỗi ràng buộc JDBC không được nuốt để rồi commit marker nghiệp vụ. */
    @Test void databaseFailureRollsBackCaller() {
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_marker VALUES (1)");
            writer.write(recipient, "CONTACT_UPDATED", "CONTACT", null, Map.of("removed", true));
        }));
        assertEquals(0, count("business_marker"));
        assertEquals(0, count("sync_events"));
    }

    /** Payload lời mời dùng DTO tối thiểu đã kiểm thử, không thêm số điện thoại vào sự kiện. */
    @Test void requestEventPreservesMinimalNestedProfiles() throws Exception {
        var dto = new ContactRequestDto("request", new UserSummaryDto("A", "Người gửi", null),
                new UserSummaryDto("B", "Người nhận", null), "PENDING", "2026-11-18T01:00:00Z", null);
        tx.executeWithoutResult(status -> writer.write(recipient, "CONTACT_REQUEST_CREATED", "CONTACT_REQUEST", "request", dto));
        var payload = json.readTree(jdbc.queryForObject("SELECT payload_json FROM sync_events", String.class));
        assertEquals("request", payload.path("id").asText());
        assertFalse(payload.path("requester").has("phone"));
        assertFalse(payload.path("addressee").has("phone"));
        assertEquals(3, payload.path("requester").size());
        assertEquals(3, payload.path("addressee").size());
    }
}
