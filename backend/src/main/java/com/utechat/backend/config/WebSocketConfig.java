package com.utechat.backend.config;

import com.utechat.backend.websocket.ChatWebSocketHandler;
import com.utechat.backend.websocket.WsAuthHandshakeInterceptor;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Đăng ký endpoint WebSocket thuần JSON /ws (không dùng STOMP theo ADR-008),
 * gắn interceptor kiểm tra Bearer handshake, chặn JWT trên URL query và nới bộ đệm text lên 64KB cho WebRTC SDP.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private static final int MAX_TEXT_BUFFER_BYTES = 65_536;

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

    /**
     * Nới giới hạn khung văn bản WebSocket lên 64KB trên Tomcat thật để các gói SDP WebRTC lớn không bị ngắt mã 1009,
     * đồng thời bỏ qua an toàn khi chạy kiểm thử MockMvc (không có ServerContainer trong MockServletContext).
     */
    @Bean
    public SmartInitializingSingleton webSocketBufferSizeInitializer(ObjectProvider<ServletContext> servletContextProvider) {
        return () -> {
            ServletContext servletContext = servletContextProvider.getIfAvailable();
            if (servletContext == null) {
                return;
            }
            Object attr = servletContext.getAttribute("jakarta.websocket.server.ServerContainer");
            if (attr instanceof ServerContainer container) {
                container.setDefaultMaxTextMessageBufferSize(MAX_TEXT_BUFFER_BYTES);
                container.setDefaultMaxBinaryMessageBufferSize(MAX_TEXT_BUFFER_BYTES);
            }
        };
    }
}
