package com.utechat.backend.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Thông tin phiên thiết bị và hạn dùng JWT gắn vào request khi xác thực thành công,
 * được WsAuthHandshakeInterceptor sao chép sang WebSocketSession để kiểm tra định kỳ và đóng 4401.
 */
public record WsAuthMeta(UUID deviceRowId, long issuedAtMs, Instant tokenExpiresAt) {
    /** Tên attribute lưu trong HttpServletRequest. */
    public static final String REQ_ATTR_WS_META = "UTECHAT_WS_AUTH_META";
}
