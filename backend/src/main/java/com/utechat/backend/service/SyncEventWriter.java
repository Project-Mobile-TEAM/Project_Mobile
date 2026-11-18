package com.utechat.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Sự kiện phải commit hoặc rollback cùng thay đổi nghiệp vụ; Phase 5 sẽ đọc để đồng bộ. */
@Service
public class SyncEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SyncEventWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Không tự mở transaction riêng: dữ liệu nghiệp vụ và sự kiện phải có cùng kết quả commit/rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID recipient, String event, String entity, String id, Object payload) {
        try {
            jdbc.update("INSERT INTO sync_events(user_id,event_type,entity_type,entity_id,payload_json) VALUES (?,?,?,?,?::jsonb)",
                    recipient,event,entity,id,json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // Ném lỗi để transaction gọi bị rollback; không bỏ qua sự kiện chưa mã hóa được.
            throw new IllegalStateException("Cannot encode sync event", e);
        }
    }
}
