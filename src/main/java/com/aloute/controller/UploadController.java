package com.aloute.controller;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ImageStorageService;
import com.aloute.service.MediaAssetLifecycleService;
import com.aloute.service.MediaUploadModerationService;
import com.aloute.service.MediaUploadRateLimitService;
import com.aloute.service.UploadContentValidator;
import com.aloute.service.UploadPurpose;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/upload")
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private static final long MAX_IMAGE_SIZE_BYTES = 15L * 1024 * 1024;
    private static final long MAX_CHAT_FILE_SIZE_BYTES = 1024L * 1024;

    private final UploadContentValidator contentValidator = new UploadContentValidator();
    private final ImageStorageService imageStorageService;
    private final MediaUploadModerationService uploadModeration;
    private final MediaAssetLifecycleService mediaAssets;
    private final MediaUploadRateLimitService uploadLimits;

    @Value("${app.upload.max-file-size:50MB}")
    private DataSize maxUploadSize;

    public UploadController(
            ImageStorageService imageStorageService,
            MediaUploadModerationService uploadModeration,
            MediaAssetLifecycleService mediaAssets,
            MediaUploadRateLimitService uploadLimits) {
        this.imageStorageService = imageStorageService;
        this.uploadModeration = uploadModeration;
        this.mediaAssets = mediaAssets;
        this.uploadLimits = uploadLimits;
    }

    @PostMapping("/media")
    public ApiResponse<Map<String, String>> uploadMedia(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "purpose", defaultValue = "general") String requestedPurpose) {
        Long uploaderId = requireAuthenticated(authentication);
        uploadLimits.assertAllowed(uploaderId);
        validateFile(file, maxUploadSize.toBytes(), "Tệp vượt quá dung lượng cho phép (" + maxUploadSize + ")");
        UploadContentValidator.DetectedType detectedType = contentValidator.validateMedia(file);
        UploadPurpose purpose = UploadPurpose.fromRequest(requestedPurpose);
        if (!purpose.allows(detectedType)) {
            throw new BadRequestException(
                    "Loại tệp không được hỗ trợ cho mục đích " + purpose.value());
        }

        return storeMedia(file, detectedType, purpose, uploaderId);
    }

    @PostMapping("/file")
    public ApiResponse<Map<String, String>> uploadChatFile(
            Authentication authentication,
            @RequestParam("file") MultipartFile file) {
        Long uploaderId = requireAuthenticated(authentication);
        uploadLimits.assertAllowed(uploaderId);
        validateFile(file, maxUploadSize.toBytes(), "Tệp vượt quá dung lượng cho phép (" + maxUploadSize + ")");
        return storeMedia(file, UploadContentValidator.DetectedType.FILE, UploadPurpose.CHAT, uploaderId);
    }

    private ApiResponse<Map<String, String>> storeMedia(
            MultipartFile file, UploadContentValidator.DetectedType detectedType,
            UploadPurpose purpose, Long uploaderId) {
        if (detectedType == UploadContentValidator.DetectedType.FILE) {
            validateFile(file, MAX_CHAT_FILE_SIZE_BYTES, "Tệp không được vượt quá 1MB.");
        }
        final Map<String, String> uploaded;
        try {
            uploaded = imageStorageService.uploadMedia(file, detectedType, purpose);
        } catch (IOException e) {
            log.warn("Media upload failed ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Không thể tải tệp lên lúc này. Vui lòng thử lại.");
        }
        try {
            mediaAssets.registerUpload(uploaded, purpose, uploaderId);
        } catch (RuntimeException trackingFailure) {
            uploadModeration.deleteQuietly(uploaded);
            log.error("Unable to register managed media (purpose={}, cause={})",
                    purpose.value(), trackingFailure.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể lưu thông tin quản lý tệp. Vui lòng thử lại.");
        }

        // Posts are checked by PostModerationWorker after the post becomes PENDING. Other public
        // purposes are checked before this endpoint returns a usable URL; upload timeout is capped.
        if (purpose != UploadPurpose.POST && detectedType != UploadContentValidator.DetectedType.FILE) {
            uploadModeration.requireApproved(uploaded,
                    detectedType == UploadContentValidator.DetectedType.IMAGE ? "image" : "video",
                    purpose.value());
        }
        return ApiResponse.success("Tải tệp thành công", uploaded);
    }

    @PostMapping("/image")
    public ApiResponse<Map<String, String>> uploadImage(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "purpose", defaultValue = "general") String requestedPurpose) {
        Long uploaderId = requireAuthenticated(authentication);
        uploadLimits.assertAllowed(uploaderId);
        validateFile(file, MAX_IMAGE_SIZE_BYTES, "Ảnh vượt quá dung lượng cho phép (15MB)");
        contentValidator.validateImage(file);
        UploadPurpose purpose = UploadPurpose.fromRequest(requestedPurpose);

        final Map<String, String> uploaded;
        try {
            uploaded = imageStorageService.uploadImageDetails(file, purpose);
        } catch (IOException e) {
            log.warn("Image upload failed ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên lúc này. Vui lòng thử lại.");
        }
        try {
            mediaAssets.registerUpload(uploaded, purpose, uploaderId);
        } catch (RuntimeException trackingFailure) {
            uploadModeration.deleteQuietly(uploaded);
            log.error("Unable to register managed image (purpose={}, cause={})",
                    purpose.value(), trackingFailure.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể lưu thông tin quản lý ảnh. Vui lòng thử lại.");
        }

        if (purpose != UploadPurpose.POST) uploadModeration.requireApproved(uploaded, "image", purpose.value());
        return ApiResponse.success("Tải ảnh lên thành công", uploaded);
    }

    private Long requireAuthenticated(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) throw new UnauthorizedException("Cần đăng nhập để tải tệp lên");
        return userId;
    }

    private void validateFile(MultipartFile file, long maxBytes, String sizeMessage) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Vui lòng chọn tệp");
        if (file.getSize() > maxBytes) throw new BadRequestException(sizeMessage);
    }
}
