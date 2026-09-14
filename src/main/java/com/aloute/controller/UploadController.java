package com.aloute.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ImageStorageService;

/**
 * Upload ảnh dùng chung (ảnh đính kèm bài viết). Trả về URL Cloudinary để
 * frontend gắn vào CreatePostRequest.imageUrl trước khi gọi POST /api/posts.
 * Tách riêng khỏi PostController vì upload ảnh xong người dùng có thể vẫn
 * đang gõ nội dung — không muốn ép phải gửi cả 2 việc trong 1 request.
 */
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private static final long MAX_IMAGE_SIZE_BYTES = 100L * 1024 * 1024; // 100MB

    @Value("${app.upload.max-file-size:2GB}")
    private DataSize maxUploadSize;

    private final ImageStorageService imageStorageService;

    public UploadController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
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
            throw new BadRequestException("Tệp vượt quá dung lượng cho phép (" + maxUploadSize + ")");
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("Không xác định được định dạng tệp");
        }

        try {
            return ApiResponse.success("Tải tệp thành công", imageStorageService.uploadMedia(file));
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
            String url = imageStorageService.uploadImage(file);
            return ApiResponse.success("Tải ảnh lên thành công", Map.of("url", url));
        } catch (IOException e) {
            throw new BadRequestException("Tải ảnh lên thất bại: " + e.getMessage());
        }
    }
}