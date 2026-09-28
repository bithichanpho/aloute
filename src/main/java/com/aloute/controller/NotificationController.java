package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.NotificationResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.NotificationService;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/notifications")
@Validated
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> getMyNotifications(
            Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(notificationService.getMyNotifications(userId));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> getUnreadCount(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(Map.of("count", notificationService.countUnread(userId)));
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllAsRead(Authentication authentication) {
        Long userId = requireUserId(authentication);
        notificationService.markAllAsRead(userId);
        return ApiResponse.success("Đã đánh dấu tất cả là đã đọc", null);
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã thông báo không hợp lệ") Long notificationId) {
        notificationService.markAsRead(requireUserId(authentication), notificationId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{notificationId}")
    public ApiResponse<Void> delete(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã thông báo không hợp lệ") Long notificationId) {
        notificationService.delete(requireUserId(authentication), notificationId);
        return ApiResponse.success(null);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
