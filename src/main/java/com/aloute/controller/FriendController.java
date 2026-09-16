package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.FriendService;
import com.aloute.service.UserService;

@RestController
@RequestMapping("/api/users")
public class FriendController {

    private final FriendService friendService;
    private final UserService userService;

    public FriendController(FriendService friendService, UserService userService) {
        this.friendService = friendService;
        this.userService = userService;
    }

    /** Bật/tắt theo dõi 1 user — trả về true nếu SAU thao tác là đang follow. */
    @PostMapping("/{userId}/follow")
    public ApiResponse<Boolean> toggleFollow(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = requireUserId(authentication);
        return ApiResponse.success(friendService.toggleFollow(currentUserId, userId));
    }

    @GetMapping("/{userId}/following")
    public ApiResponse<List<UserProfileDto>> getFollowing(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        List<Long> ids = friendService.getFollowingIds(userId);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    @GetMapping("/{userId}/followers")
    public ApiResponse<List<UserProfileDto>> getFollowers(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        List<Long> ids = friendService.getFollowerIds(userId);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    /** Bạn bè = 2 người đã follow lẫn nhau. Đây là những người có thể nhắn tin cho nhau. */
    @GetMapping("/{userId}/friends")
    public ApiResponse<List<UserProfileDto>> getFriends(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        List<Long> ids = friendService.getMutualFriendIds(userId);
        return ApiResponse.success(toProfiles(ids, currentUserId));
    }

    /**
     * Dựng hồ sơ theo lô: ~5 query cho cả danh sách, thay vì ~9 query mỗi người
     * như bản cũ (gọi getProfile trong vòng lặp).
     */
    private List<UserProfileDto> toProfiles(List<Long> ids, Long currentUserId) {
        return userService.getProfiles(ids, currentUserId);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}