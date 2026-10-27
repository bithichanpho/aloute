package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.ChangePasswordRequest;
import com.aloute.dto.request.UpdateProfileRequest;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.security.ClientIpResolver;
import com.aloute.service.AuthService;
import com.aloute.service.ImageStorageService;
import com.aloute.service.MediaUploadModerationService;
import com.aloute.service.MediaAssetLifecycleService;
import com.aloute.service.MediaUploadRateLimitService;
import com.aloute.service.UploadContentValidator;
import com.aloute.service.UploadPurpose;
import com.aloute.service.UserService;
import com.aloute.service.AuthRateLimitService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;
    private final ImageStorageService imageStorageService;
    private final MediaUploadModerationService uploadModeration;
    private final MediaAssetLifecycleService mediaAssets;
    private final MediaUploadRateLimitService uploadLimits;
    private final AuthService authService;
    private final AuthRateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final UploadContentValidator contentValidator = new UploadContentValidator();
    private static final long MAX_AVATAR_SIZE_BYTES = 15L * 1024 * 1024;

    public UserController(
            UserService userService,
            ImageStorageService imageStorageService,
            MediaUploadModerationService uploadModeration,
            MediaAssetLifecycleService mediaAssets,
            MediaUploadRateLimitService uploadLimits,
            AuthService authService,
            AuthRateLimitService rateLimitService,
            ClientIpResolver clientIpResolver) {
        this.userService = userService;
        this.imageStorageService = imageStorageService;
        this.uploadModeration = uploadModeration;
        this.mediaAssets = mediaAssets;
        this.uploadLimits = uploadLimits;
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileDto> getMe(Authentication authentication) {
        Long userId = requireUserId(authentication);
        limitProfileReads(userId);
        return ApiResponse.success(userService.getProfile(userId, userId));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileDto> getProfile(
            Authentication authentication,
            HttpServletRequest request,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        limitProfileReads(currentUserId != null
                ? "user:" + currentUserId
                : "ip:" + clientIpResolver.resolve(request));
        return ApiResponse.success(userService.getProfile(userId, currentUserId));
    }

    private void limitProfileReads(Long userId) {
        limitProfileReads("user:" + userId);
    }

    private void limitProfileReads(String subject) {
        rateLimitService.checkAndRecord(
                "PROFILE_READ", subject, 300,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tải trang cá nhân quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa truy vấn trang cá nhân do quá nhiều yêu cầu.");
    }

    @PutMapping("/me")
    public ApiResponse<UserProfileDto> updateProfile(
            Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(
                "Đã cập nhật trang cá nhân", userService.updateProfile(userId, request));
    }

    @PostMapping("/me/avatar")
    public ApiResponse<UserProfileDto> updateAvatar(
            Authentication authentication, @RequestParam("avatar") MultipartFile avatar) {
        Long userId = requireUserId(authentication);
        uploadLimits.assertAllowed(userId);
        if (avatar == null || avatar.isEmpty()) {
            throw new BadRequestException("Vui lòng chọn ảnh đại diện");
        }
        if (avatar.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new BadRequestException("Ảnh đại diện vượt quá dung lượng cho phép (15MB)");
        }
        contentValidator.validateImage(avatar);
        // Use the raw database URL, not the transformed display URL, for lifecycle/reference checks.
        String previousAvatar = userService.findUserBasicInfo(userId)
                .map(com.aloute.model.entity.User::getAvatarUrl)
                .orElse(null);

        final Map<String, String> uploaded;
        try {
            uploaded = imageStorageService.uploadImageDetails(avatar, UploadPurpose.AVATAR);
        } catch (IOException e) {
            throw new BadRequestException("Tải ảnh lên thất bại. Vui lòng thử lại.");
        }
        try {
            mediaAssets.registerUpload(uploaded, UploadPurpose.AVATAR, userId);
        } catch (RuntimeException trackingFailure) {
            uploadModeration.deleteQuietly(uploaded);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể lưu thông tin quản lý ảnh. Vui lòng thử lại.");
        }

        // The same per-user Redis upload budget applies even though this endpoint bypasses /api/upload.
        uploadModeration.requireApproved(uploaded, "image", "avatar");
        try {
            userService.updateAvatar(userId, uploaded.get("url"));
        } catch (RuntimeException persistenceFailure) {
            uploadModeration.deleteQuietly(uploaded);
            throw persistenceFailure;
        }
        try {
            mediaAssets.attachManagedReference(uploaded.get("url"), UploadPurpose.AVATAR,
                    String.valueOf(userId), "USER_AVATAR", String.valueOf(userId));
            if (previousAvatar != null && !previousAvatar.equals(uploaded.get("url"))) {
                mediaAssets.deleteIfUnreferenced(previousAvatar);
            }
        } catch (RuntimeException cleanupFailure) {
            // The scheduled orphan cleaner will reconcile a still-UPLOADED asset; don't fail the saved avatar update.
            org.slf4j.LoggerFactory.getLogger(UserController.class).warn(
                    "Avatar lifecycle reconciliation deferred (userId={}, cause={})",
                    userId, cleanupFailure.getClass().getSimpleName());
        }
        return ApiResponse.success(
                "Đã cập nhật ảnh đại diện", userService.getProfile(userId, userId));
    }

    @PostMapping("/me/change-password")
    public ApiResponse<Void> changePassword(
            Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        Long userId = requireUserId(authentication);
        if (request.getConfirmNewPassword() == null) {
            authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        } else {
            authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword(), request.getConfirmNewPassword());
        }
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
