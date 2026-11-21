package com.utechat.backend.websocket;

import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.security.JwtAuthenticationFilter;
import com.utechat.backend.security.WsAuthMeta;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Kiểm tra bảo mật tại bước HTTP Upgrade /ws (DEPLOYMENT_SECURITY.md):
 * từ chối JWT trong query string (HTTP 400, không log query), xác nhận đã qua JwtAuthenticationFilter
 * và chép thông tin thiết bị/hạn dùng JWT sang attributes của WebSocketSession.
 */
@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_DEVICE_HASH = "deviceIdHash";
    public static final String ATTR_DEVICE_ROW_ID = "deviceRowId";
    public static final String ATTR_ISSUED_AT_MS = "issuedAtMs";
    public static final String ATTR_TOKEN_EXPIRES_AT = "tokenExpiresAt";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) throws Exception {
        // Lớp phòng vệ thứ hai: từ chối ngay nếu URL chứa tham số mang JWT, tuyệt đối không ghi log rawQuery.
        if (JwtAuthenticationFilter.containsForbiddenQueryKey(request.getURI().getRawQuery())) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getBody().write("{\"error\":{\"code\":\"VALIDATION\",\"message\":\"Token must not be placed in URL\"}}"
                    .getBytes(StandardCharsets.UTF_8));
            return false;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedActor actor)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!(request instanceof ServletServerHttpRequest servletReq)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Object metaObj = servletReq.getServletRequest().getAttribute(WsAuthMeta.REQ_ATTR_WS_META);
        if (!(metaObj instanceof WsAuthMeta meta)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(ATTR_USER_ID, actor.userId());
        attributes.put(ATTR_DEVICE_HASH, actor.deviceIdHash());
        attributes.put(ATTR_DEVICE_ROW_ID, meta.deviceRowId());
        attributes.put(ATTR_ISSUED_AT_MS, meta.issuedAtMs());
        attributes.put(ATTR_TOKEN_EXPIRES_AT, meta.tokenExpiresAt());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // Không cần xử lý bổ sung sau handshake.
    }
}
