package com.aloute.controller;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ImageStorageService;
import com.aloute.service.ModerationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private static final long MAX_IMAGE_SIZE_BYTES = 100L * 1024 * 1024;

    @Value("${app.upload.max-file-size:2GB}")
    private DataSize maxUploadSize;

    private final ImageStorageService imageStorageService;
    private final ModerationService moderationService;

    public UploadController(
            ImageStorageService imageStorageService, ModerationService moderationService) {
        this.imageStorageService = imageStorageService;
        this.moderationService = moderationService;
    }

    @PostMapping("/media")
    public ApiResponse<Map<String, String>> uploadMedia(
            Authentication authentication, @RequestParam("file") MultipartFile file) {

        if (SecurityUtils.currentUserId(authentication) == null) {
            throw new UnauthorizedException("Cần đăng nhập để tải tệp lên");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Vui lòng chọn tệp");
        }
        if (file.getSize() > maxUploadSize.toBytes()) {
            throw new BadRequestException(
                    "Tệp vượt quá dung lượng cho phép (" + maxUploadSize + ")");
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("Không xác định được định dạng tệp");
        }

        try {
            Map<String, String> uploaded = imageStorageService.uploadMedia(file);
            moderate(uploaded);
            return ApiResponse.success("Tải tệp thành công", uploaded);
        } catch (IOException e) {
            throw new BadRequestException("Tải tệp thất bại: " + e.getMessage());
        }
    }

    @PostMapping("/image")
    public ApiResponse<Map<String, String>> uploadImage(
            Authentication authentication, @RequestParam("file") MultipartFile file) {

        if (SecurityUtils.currentUserId(authentication) == null) {
            throw new UnauthorizedException("Cần đăng nhập để tải ảnh lên");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Vui lòng chọn một ảnh");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new BadRequestException("Ảnh vượt quá dung lượng cho phép (100MB)");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BadRequestException("Chỉ chấp nhận file ảnh");
        }

        try {
            Map<String, String> uploaded = imageStorageService.uploadImageDetails(file);
            moderate(uploaded);
            return ApiResponse.success("Tải ảnh lên thành công", uploaded);
        } catch (IOException e) {
            throw new BadRequestException("Tải ảnh lên thất bại: " + e.getMessage());
        }
    }

    private void moderate(Map<String, String> uploaded) throws IOException {
        String type = uploaded.getOrDefault("type", "file");
        if (!"image".equals(type) && !"video".equals(type)) return;
        if (moderationService.isMediaApproved(uploaded.get("url"), type)) return;
        imageStorageService.deleteMedia(uploaded.get("publicId"), uploaded.get("resourceType"));
        throw new BadRequestException("Ảnh hoặc video không vượt qua kiểm duyệt an toàn");
    }
}
