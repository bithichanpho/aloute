package com.aloute.config;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Chạy trước khi nâng cấp kết nối HTTP -> WebSocket (kể cả các request
 * xhr-streaming/polling của SockJS). Đọc cookie ALOUTE_TOKEN — CÙNG cookie
 * mà trình duyệt tự gửi kèm khi tải trang /chat — để xác thực, không đòi hỏi
 * JavaScript phải tự tay đọc/gắn token vào đâu cả.
 *
 * Username hợp lệ được lưu vào "attributes" của WebSocketSession, và
 * AloutueHandshakeHandler (bên dưới) dùng nó để gán Principal thật cho phiên.
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "username";

    private final JwtUtil jwtUtil;

    public JwtHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String token = extractToken(httpRequest);

            if (token != null) {
                try {
                    String username = jwtUtil.extractUsername(token);
                    if (username != null && jwtUtil.isTokenValid(token, username)) {
                        attributes.put(ATTR_USERNAME, username);
                    }
                } catch (Exception ignored) {
                    // token hỏng/hết hạn — để trống, sẽ bị từ chối khi subscribe kênh riêng tư
                }
            }
        }
        // Vẫn cho bắt tay tiếp tục dù chưa xác thực được — client chưa đăng nhập vẫn
        // kết nối được nhưng sẽ không nhận/gửi được tin nhắn (không có Principal).
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

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