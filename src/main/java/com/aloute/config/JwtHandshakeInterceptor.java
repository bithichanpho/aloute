package com.aloute.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_AUTH_FAILURE = "tokenAuthFailure";

    private final JwtUtil jwtUtil;

    public JwtHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {

        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            attributes.put(ATTR_AUTH_FAILURE, "Access token missing; refresh required");
            return true;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        String token = extractToken(httpRequest);
        if (token == null || token.isBlank()) {
            attributes.put(ATTR_AUTH_FAILURE, "Access token missing; refresh required");
            return true;
        }

        try {
            String username = jwtUtil.extractUsername(token);
            if (username == null || !jwtUtil.isTokenValid(token, username)) {
                attributes.put(ATTR_AUTH_FAILURE, "Access token expired or invalid; refresh required");
                return true;
            }
            attributes.put(ATTR_USERNAME, username);
            attributes.put("tokenIssuedAt", jwtUtil.issuedAt(token));
            attributes.put("tokenExpiresAt", jwtUtil.expiresAt(token));
            return true;
        } catch (RuntimeException invalidToken) {
            log.debug("WebSocket access token validation failed ({})",
                    invalidToken.getClass().getSimpleName());
            attributes.put(ATTR_AUTH_FAILURE, "Access token expired or invalid; refresh required");
            return true;
        }
    }

    @Override
    public void afterHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            Exception exception) {}

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (JwtAuthFilter.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
