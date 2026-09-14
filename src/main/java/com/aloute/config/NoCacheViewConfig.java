package com.aloute.config;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Các trang HTML do ViewController render (/, /feed, /chat, /profile/**, ...) tự quyết định
 * đăng nhập hay chưa dựa vào cookie ALOUTE_TOKEN NGAY LÚC RENDER. Nếu không có header
 * chống cache, trình duyệt (nút Back/Forward, bfcache) hoặc một proxy/CDN phía trước ứng dụng
 * có thể lưu lại bản HTML đã render cho một người đã đăng nhập, rồi trả lại y nguyên cho một
 * lượt truy cập khác (kể cả khi chưa đăng nhập) — tạo cảm giác "mới vào đã thấy /feed dù chưa login".
 *
 * Interceptor này gắn "Cache-Control: no-store" cho toàn bộ trang HTML để mỗi lần vào URL
 * đều được render lại từ server dựa trên trạng thái đăng nhập THẬT của request đó.
 */
@Component
public class NoCacheViewConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
                response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                response.setHeader("Pragma", "no-cache");
                response.setHeader("Expires", "0");
                return true;
            }
        }).addPathPatterns(
                "/", "/login", "/register", "/verify-email",
                "/forgot-password", "/reset-password",
                "/feed", "/chat", "/profile/**", "/compose",
                "/search", "/activity", "/insights", "/saved"
        );
    }
}
