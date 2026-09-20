package com.aloute.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                // Không cho trang bị nhúng trong <iframe> của site KHÁC -> chặn clickjacking.
                // Dùng sameOrigin (không phải deny) để không ảnh hưởng SockJS iframe fallback
                // (chỉ kích hoạt trên trình duyệt rất cũ không hỗ trợ WebSocket/XHR streaming).
                .frameOptions(frame -> frame.sameOrigin())
                // Không để trình duyệt tự "đoán" content-type khác với header Content-Type thật sự.
                .contentTypeOptions(contentType -> {})
                // Không gửi kèm URL đầy đủ của trang hiện tại khi bấm link sang site khác.
                .referrerPolicy(referrer -> referrer
                        .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // HSTS: sau lần truy cập HTTPS đầu tiên, trình duyệt tự động chuyển các lần
                // sau sang HTTPS luôn, kể cả khi người dùng gõ http:// hoặc bấm link http.
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000)))
            .authorizeHttpRequests(auth -> auth
                // Trang giao diện (HTML) + tài nguyên tĩnh: luôn công khai.
                // ViewController tự kiểm tra đăng nhập và redirect về /login nếu cần
                // cho những trang bắt buộc đăng nhập (feed, chat, compose, profile/me...).
                .requestMatchers(
                        "/", "/login", "/register", "/verify-email",
                        "/forgot-password", "/reset-password",
                        "/feed", "/chat", "/profile/**", "/compose",
                        "/search", "/activity", "/insights", "/saved",
                        "/css/**", "/js/**", "/images/**", "/favicon.ico",
                        "/error"
                ).permitAll()
                // API xác thực + WebSocket handshake: công khai (WebSocket tự xác thực qua STOMP CONNECT)
                .requestMatchers("/api/auth/**", "/ws/**").permitAll()
                // Xem bài viết công khai (feed, bài của 1 user) không bắt buộc đăng nhập để hiển thị demo,
                // nhưng thao tác (like, comment, đăng bài...) vẫn cần JWT vì nằm dưới các path khác.
                .requestMatchers(HttpMethod.GET, "/api/posts/feed", "/api/posts/user/**").permitAll()
                // Mọi API còn lại bắt buộc phải có JWT hợp lệ
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}