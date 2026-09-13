package com.aloute.controller;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.ChangePasswordRequest;
import com.aloute.dto.request.UpdateProfileRequest;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AuthService;
import com.aloute.service.ImageStorageService;
import com.aloute.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final ImageStorageService imageStorageService;
    private final AuthService authService;

    public UserController(UserService userService, ImageStorageService imageStorageService, AuthService authService) {
        this.userService = userService;
        this.imageStorageService = imageStorageService;
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileDto> getMe(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(userService.getProfile(userId, userId));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileDto> getProfile(Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(userService.getProfile(userId, currentUserId));
    }

    @PutMapping("/me")
    public ApiResponse<UserProfileDto> updateProfile(
            Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success("Đã cập nhật trang cá nhân", userService.updateProfile(userId, request));
    }

    /** Upload avatar mới lên Cloudinary rồi lưu URL vào profile. */
    @PostMapping("/me/avatar")
    public ApiResponse<UserProfileDto> updateAvatar(
            Authentication authentication, @RequestParam("avatar") MultipartFile avatar) {

        Long userId = requireUserId(authentication);
        if (avatar == null || avatar.isEmpty()) {
            throw new BadRequestException("Vui lòng chọn ảnh đại diện");
        }
        try {
            String url = imageStorageService.uploadImage(avatar);
            userService.updateAvatar(userId, url);
            return ApiResponse.success("Đã cập nhật ảnh đại diện", userService.getProfile(userId, userId));
        } catch (IOException e) {
            throw new BadRequestException("Tải ảnh lên thất bại: " + e.getMessage());
        }
    }

    /** Đổi mật khẩu khi đã đăng nhập — bắt buộc nhập đúng mật khẩu hiện tại. */
    @PostMapping("/me/change-password")
    public ApiResponse<Void> changePassword(
            Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        Long userId = requireUserId(authentication);
        authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.success("Đã đổi mật khẩu thành công", null);
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}