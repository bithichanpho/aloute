package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.NotificationResponse;
import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.security.SecurityUtils;
import com.aloute.notifications.api.Notifications;
import com.aloute.service.AuthRateLimitService;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/notifications")
@Validated
public class NotificationController {

    private final Notifications notificationService;
    private final AuthRateLimitService rateLimitService;

    public NotificationController(
            Notifications notificationService, AuthRateLimitService rateLimitService) {
        this.notificationService = notificationService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> getMyNotifications(
            Authentication authentication) {
        Long userId = requireUserId(authentication);
        limitReads(userId);
        return ApiResponse.success(notificationService.getMyNotifications(userId));
    }

    @GetMapping("/cursor")
    public ApiResponse<NotificationCursorPage> getMyNotificationsCursor(
            Authentication authentication,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        Long userId = requireUserId(authentication);
        limitReads(userId);
        return ApiResponse.success(notificationService.getMyNotifications(userId, beforeId, size));
    }

    private void limitReads(Long userId) {
        rateLimitService.checkAndRecord(
                "NOTIFICATION_READ", userId.toString(), 180,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tải thông báo quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa truy vấn thông báo do quá nhiều yêu cầu.");
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
