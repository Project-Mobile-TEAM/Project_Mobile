package com.utechat.data.remote.model;

/** DTO phản hồi readiness: chỉ trạng thái và thời gian, không chứa thông tin kết nối. */
public class HealthDto {
    private String status; // UP hoặc DOWN theo hợp đồng backend.
    private String database; // CONNECTED hoặc trạng thái thất bại cố định.
    private String timestamp; // UTC ISO-8601, dùng cho chẩn đoán.

    public HealthDto() { } // Gson cần đối tượng để ánh xạ JSON.
    public HealthDto(String status, String database, String timestamp) {
        this.status = status; this.database = database; this.timestamp = timestamp;
    }
    public String getStatus() { return status; }
    public String getDatabase() { return database; }
    public String getTimestamp() { return timestamp; }
}
