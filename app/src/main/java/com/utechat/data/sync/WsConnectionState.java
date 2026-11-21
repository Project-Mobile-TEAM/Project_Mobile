package com.utechat.data.sync;

/** Trạng thái kết nối thời gian thực dùng để hiển thị biểu ngữ kết nối trên giao diện. */
public enum WsConnectionState {
    /** WebSocket đã mở VÀ SyncEngine đã đồng bộ bắt kịp hoàn toàn (hasMore = false, retryAfterMs = null). */
    CONNECTED,
    /** Đang kết nối lại, chờ backoff hoặc đang chờ SyncEngine bắt kịp dữ liệu sau khi mở socket. */
    RECONNECTING,
    /** Đã ngắt kết nối chủ động, chưa đăng nhập hoặc phiên đã bị thu hồi (4401/401). */
    DISCONNECTED
}
