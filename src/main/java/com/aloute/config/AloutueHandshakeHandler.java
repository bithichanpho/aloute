package com.aloute.config;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Gán Principal cho phiên WebSocket dựa trên username mà JwtHandshakeInterceptor
 * đã xác thực và lưu vào attributes. NotificationService/ChatController dùng
 * SimpMessagingTemplate#convertAndSendToUser(username, ...) nên Principal.getName()
 * ở đây BẮT BUỘC phải trùng với user.getUsername() trong DB.
 *
 * Nếu chưa xác thực được (không có cookie/token hợp lệ), gán 1 Principal ẩn danh
 * ngẫu nhiên để tránh nhiều tab/khách cùng lúc bị dồn chung 1 "user" ảo.
 */
public class AloutueHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(@Nullable ServerHttpRequest request, @Nullable WebSocketHandler wsHandler,
            @Nullable Map<String, Object> attributes) {

        Object username = attributes != null ? attributes.get(JwtHandshakeInterceptor.ATTR_USERNAME) : null;
        String name = username != null ? username.toString() : "anonymous-" + UUID.randomUUID();

        return () -> name;
    }
}