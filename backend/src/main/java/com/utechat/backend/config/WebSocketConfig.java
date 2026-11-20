package com.utechat.backend.config;

import com.utechat.backend.websocket.ChatWebSocketHandler;
import com.utechat.backend.websocket.WsAuthHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Đăng ký endpoint WebSocket thuần JSON /ws (không dùng STOMP theo ADR-008),
 * gắn interceptor kiểm tra Bearer handshake và chặn JWT trên URL query.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;
    private final WsAuthHandshakeInterceptor authInterceptor;

    public WebSocketConfig(ChatWebSocketHandler handler, WsAuthHandshakeInterceptor authInterceptor) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
