package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.security.SecurityUtils;
import com.aloute.service.SocialFeatureService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api")
@Validated
public class SocialFeatureController {
    private final SocialFeatureService service;

    public SocialFeatureController(SocialFeatureService service) {
        this.service = service;
    }

    public record Report(
            @NotBlank @Pattern(regexp = "POST|COMMENT|USER", message = "Loại báo cáo không hợp lệ") String targetType,
            @NotBlank @Size(max = 100) String targetId,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Decision(
            @NotBlank @Pattern(regexp = "POST|COMMENT|USER", message = "Loại nội dung không hợp lệ") String targetType,
            @NotBlank @Size(max = 100) String targetId,
            @NotBlank @Pattern(regexp = "APPROVE|HIDE|DISMISS", message = "Quyết định không hợp lệ") String decision,
            @Size(max = 256) String reportId) {}

    private Long user(Authentication auth) {
        Long id = SecurityUtils.currentUserId(auth);
        if (id == null) throw new UnauthorizedException("Cần đăng nhập");
        return id;
    }

    @GetMapping("/blocks")
    public ApiResponse<?> blocks(Authentication a) {
        return ApiResponse.success(service.blocks(user(a)));
    }

    @PutMapping("/blocks/{id}")
    public ApiResponse<?> block(Authentication a, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long id) {
        service.block(user(a), id, true);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/blocks/{id}")
    public ApiResponse<?> unblock(Authentication a, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long id) {
        service.block(user(a), id, false);
        return ApiResponse.success(null);
    }

    @GetMapping("/follow-requests")
    public ApiResponse<?> requests(Authentication a) {
        return ApiResponse.success(service.requests(user(a)));
    }

    @PutMapping("/follow-requests/{id}")
    public ApiResponse<?> accept(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã yêu cầu") String id) {
        service.decideFollow(user(a), id, true);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/follow-requests/{id}")
    public ApiResponse<?> reject(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã yêu cầu") String id) {
        service.decideFollow(user(a), id, false);
        return ApiResponse.success(null);
    }

    @PostMapping("/reports")
    public ApiResponse<?> report(Authentication a, @Valid @RequestBody Report r) {
        return ApiResponse.success(
                service.report(user(a), r.targetType(), r.targetId(), r.reason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/moderation")
    public ApiResponse<?> queue(
            Authentication a,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "Kích thước trang phải lớn hơn 0") @Max(value = 50, message = "Kích thước trang tối đa là 50") int size) {
        return ApiResponse.success(service.queue(user(a), page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/content/{type}/{id}")
    public ApiResponse<?> reviewContent(
            Authentication a,
            @PathVariable @Pattern(regexp = "(?i)POST|COMMENT|USER", message = "Loại nội dung không hợp lệ") String type,
            @PathVariable @NotBlank(message = "Thiếu mã nội dung") String id) {
        return ApiResponse.success(service.reviewContent(user(a), type, id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/admin/moderation")
    public ApiResponse<?> moderate(Authentication a, @Valid @RequestBody Decision d) {
        service.moderate(user(a), d.targetType(), d.targetId(), d.decision(), d.reportId());
        return ApiResponse.success(null);
    }

    @GetMapping("/saved")
    public ApiResponse<?> saved(Authentication a, @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") @Max(value = 100, message = "Trang quá sâu") int page) {
        return ApiResponse.success(service.saved(user(a), page));
    }

    @PutMapping("/posts/{id}/saved")
    public ApiResponse<?> save(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã bài viết") String id) {
        service.save(user(a), id, true);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/posts/{id}/saved")
    public ApiResponse<?> unsave(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã bài viết") String id) {
        service.save(user(a), id, false);
        return ApiResponse.success(null);
    }

    @PutMapping("/posts/{id}")
    public ApiResponse<?> edit(
            Authentication a,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String id,
            @Valid @RequestBody CreatePostRequest r) {
        service.edit(user(a), id, r);
        return ApiResponse.success(null);
    }

    @GetMapping("/posts/{id}/history")
    public ApiResponse<?> history(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã bài viết") String id) {
        return ApiResponse.success(service.history(user(a), id));
    }

}
