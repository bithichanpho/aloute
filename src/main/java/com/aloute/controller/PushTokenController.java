package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.PushFidRequest;
import com.aloute.security.SecurityUtils;
import com.aloute.service.PushNotificationService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushTokenController {

    private final PushNotificationService pushNotificationService;

    public PushTokenController(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Boolean>> register(
            Authentication authentication, @Valid @RequestBody PushFidRequest request) {
        Long userId = requireUserId(authentication);
        pushNotificationService.register(userId, request.getFid());
        return ApiResponse.success("Đã bật thông báo đẩy", Map.of("enabled", true));
    }

    @DeleteMapping("/register")
    public ApiResponse<Map<String, Boolean>> unregister(
            Authentication authentication, @Valid @RequestBody PushFidRequest request) {
        Long userId = requireUserId(authentication);
        pushNotificationService.unregister(userId, request.getFid());
        return ApiResponse.success("Đã tắt thông báo đẩy trên thiết bị này", Map.of("enabled", false));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Bạn cần đăng nhập để cấu hình thông báo đẩy");
        }
        return userId;
    }
}
