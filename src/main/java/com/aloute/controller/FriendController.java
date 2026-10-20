package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.FriendService;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.UserService;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/users/{userId}")
@Validated
public class FriendController {

    private final FriendService friendService;
    private final UserService userService;
    private final AuthRateLimitService rateLimitService;

    public FriendController(
            FriendService friendService,
            UserService userService,
            AuthRateLimitService rateLimitService) {
        this.friendService = friendService;
        this.userService = userService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/follow")
    public ApiResponse<Boolean> toggleFollow(
            Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "FOLLOW", currentUserId.toString(), 60,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn follow quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa follow do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(friendService.toggleFollow(currentUserId, userId));
    }

    @GetMapping("/following")
    public ApiResponse<List<UserProfileDto>> getFollowing(
            Authentication authentication, @PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        userService.getProfile(userId, currentUserId);
        List<Long> ids = friendService.getFollowingIds(userId, page, size);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    @GetMapping("/followers")
    public ApiResponse<List<UserProfileDto>> getFollowers(
            Authentication authentication, @PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        userService.getProfile(userId, currentUserId);
        List<Long> ids = friendService.getFollowerIds(userId, page, size);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    @GetMapping("/friends")
    public ApiResponse<List<UserProfileDto>> getFriends(
            Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        userService.getProfile(userId, currentUserId);
        List<Long> ids = friendService.getMutualFriendIds(userId);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    private List<UserProfileDto> toProfiles(List<Long> ids, Long currentUserId) {
        return userService.getProfiles(ids, currentUserId);
    }

    @GetMapping("/follow-status")
    public ApiResponse<String> followStatus(
            Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        return ApiResponse.success(
                friendService.followStatus(requireUserId(authentication), userId));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
