package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.CloseFriendService;
import com.aloute.service.UserService;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import jakarta.validation.constraints.Positive;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/close-friends/{userId}")
@Validated
public class CloseFriendController {

    private final CloseFriendService closeFriendService;
    private final UserService userService;

    public CloseFriendController(CloseFriendService closeFriendService, UserService userService) {
        this.closeFriendService = closeFriendService;
        this.userService = userService;
    }

    @PostMapping("/request")
    public ApiResponse<String> sendRequest(
            Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = requireUserId(authentication);
        String status = closeFriendService.sendRequest(currentUserId, userId);
        String message =
                "ACCEPTED".equals(status) ? "Đã trở thành bạn thân" : "Đã gửi lời mời kết bạn thân";
        return ApiResponse.success(message, status);
    }

    @PostMapping("/accept")
    public ApiResponse<String> accept(Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = requireUserId(authentication);
        String status = closeFriendService.acceptRequest(currentUserId, userId);
        return ApiResponse.success("Đã chấp nhận kết bạn thân", status);
    }

    @DeleteMapping
    public ApiResponse<Void> remove(Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = requireUserId(authentication);
        closeFriendService.removeRelationship(currentUserId, userId);
        return ApiResponse.success("Đã cập nhật", null);
    }

    @GetMapping
    public ApiResponse<List<UserProfileDto>> list(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0)
                    @jakarta.validation.constraints.Max(10000) int page,
            @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1)
                    @jakarta.validation.constraints.Max(100) int size) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);

        List<UserProfileDto> profiles =
                userService.getProfiles(
                        closeFriendService.getCloseFriendIds(userId, page, size), currentUserId);
        return ApiResponse.success(profiles);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
