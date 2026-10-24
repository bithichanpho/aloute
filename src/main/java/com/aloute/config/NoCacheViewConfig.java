package com.aloute.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class NoCacheViewConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    @NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull Object handler) {
                                response.setHeader(
                                        "Cache-Control", "no-store, no-cache, must-revalidate");
                                response.setHeader("Pragma", "no-cache");
                                response.setHeader("Expires", "0");
                                return true;
                            }
                        })
                .addPathPatterns(
                        "/",
                        "/login",
                        "/register",
                        "/verify-email",
                        "/forgot-password",
                        "/reset-password",
                        "/verify-reset-code",
                        "/feed",
                        "/chat",
                        "/profile/**",
                        "/compose",
                        "/search",
                        "/activity",
                        "/saved",
                        "/settings",
                        "/admin/moderation");
    }
}
