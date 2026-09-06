package com.aloute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Cấu hình WebSocket (STOMP over SockJS) cho tính năng chat + thông báo real-time.
 *
 * - Endpoint bắt tay: /ws (SockJS fallback cho trình duyệt cũ / mạng chặn WS thô).
 *   Việc xác thực xảy ra NGAY ở bước bắt tay HTTP (xem JwtHandshakeInterceptor),
 *   dựa trên cookie ALOUTE_TOKEN — cùng cookie mà trang /chat đã có sẵn khi tải,
 *   nên phía client (chat.js) không cần tự quản lý/gắn token vào đâu cả.
 * - Client gửi tin nhắn tới: /app/chat.send            (xem ChatController#sendMessage)
 * - Server đẩy tin nhắn riêng cho từng user tại:  /user/queue/messages
 * - Server đẩy thông báo riêng cho từng user tại: /user/queue/notifications
 *   (NotificationService dùng convertAndSendToUser(username, ...) — username này
 *   phải khớp với Principal.getName() do AloutueHandshakeHandler gán).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new JwtHandshakeInterceptor(jwtUtil))
                .setHandshakeHandler(new AloutueHandshakeHandler())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic: broadcast (không dùng nhiều ở đây); /queue: điểm-tới-điểm (chat, notification)
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}