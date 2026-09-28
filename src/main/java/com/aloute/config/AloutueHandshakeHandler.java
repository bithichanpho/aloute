package com.aloute.config;

import org.jspecify.annotations.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

public class AloutueHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(
            @Nullable ServerHttpRequest request,
            @Nullable WebSocketHandler wsHandler,
            @Nullable Map<String, Object> attributes) {

        Object username =
                attributes != null ? attributes.get(JwtHandshakeInterceptor.ATTR_USERNAME) : null;
        String name = username != null ? username.toString() : "anonymous-" + UUID.randomUUID();

        return () -> name;
    }
}
