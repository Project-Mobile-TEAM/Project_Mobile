package com.utechat.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.websocket.SyncEventRealtimeDispatcher;
import com.utechat.backend.websocket.SyncEventRealtimeDispatcher.CommittedSyncEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ghi sự kiện vào bảng sync_events trong cùng transaction nghiệp vụ và phát WebSocket sau commit (afterCommit).
 * Giữ nguyên constructor 2 tham số để SyncEventWriterTest trên H2 tiếp tục chạy tương thích 100%.
 */
@Service
public class SyncEventWriter {
    private static final Object TX_RESOURCE_KEY = new Object();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SyncEventRealtimeDispatcher dispatcher;

    /** Constructor 2 tham số dùng cho unit test H2 (không có WebSocket dispatcher). */
    public SyncEventWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.dispatcher = null;
    }

    /** Constructor mặc định cho Spring Boot: gắn thêm SyncEventRealtimeDispatcher phát WS sau commit. */
    @Autowired
    public SyncEventWriter(
            JdbcTemplate jdbc,
            ObjectMapper json,
            ObjectProvider<SyncEventRealtimeDispatcher> dispatcherProvider) {
        this.jdbc = jdbc;
        this.json = json;
        this.dispatcher = dispatcherProvider == null ? null : dispatcherProvider.getIfAvailable();
    }

    /** Không tự mở transaction riêng: dữ liệu nghiệp vụ và sự kiện phải có cùng kết quả commit/rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID recipient, String event, String entity, String id, Object payload) {
        String encoded;
        try {
            encoded = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Ném lỗi để transaction gọi bị rollback; không bỏ qua sự kiện chưa mã hóa được.
            throw new IllegalStateException("Cannot encode sync event", e);
        }

        if (dispatcher == null) {
            // Đường chạy H2 test: không dùng RETURNING để giữ tương thích hoàn toàn với SyncEventWriterTest.
            jdbc.update(
                    "INSERT INTO sync_events(user_id,event_type,entity_type,entity_id,payload_json) VALUES (?,?,?,?,?::jsonb)",
                    recipient, event, entity, id, encoded);
            return;
        }

        Long eventId = jdbc.queryForObject(
                "INSERT INTO sync_events(user_id,event_type,entity_type,entity_id,payload_json) "
                        + "VALUES (?,?,?,?,?::jsonb) RETURNING id",
                Long.class,
                recipient, event, entity, id, encoded);

        if (eventId != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            registerAfterCommit(new CommittedSyncEvent(eventId, recipient, event, entity, id, payload));
        }
    }

    /** Gom các sự kiện trong cùng transaction và chỉ đẩy WebSocket trong callback afterCommit(). */
    @SuppressWarnings("unchecked")
    private void registerAfterCommit(CommittedSyncEvent item) {
        List<CommittedSyncEvent> pending = (List<CommittedSyncEvent>) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (pending == null) {
            pending = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, pending);
            List<CommittedSyncEvent> batch = pending;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.onEventsCommitted(batch);
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TX_RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(TX_RESOURCE_KEY);
                    }
                }
            });
        }
        pending.add(item);
    }
}
