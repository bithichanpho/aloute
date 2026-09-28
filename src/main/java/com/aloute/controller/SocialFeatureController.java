package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.security.SecurityUtils;
import com.aloute.service.SocialFeatureService;
import com.aloute.validation.HttpUrl;
import com.aloute.validation.AtLeastOneNotBlank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class SocialFeatureController {
    private final SocialFeatureService service;

    public record Report(
            @NotBlank @Pattern(regexp = "POST|COMMENT|USER", message = "Loại báo cáo không hợp lệ") String targetType,
            @NotBlank @Size(max = 100) String targetId,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Decision(
            @NotBlank @Pattern(regexp = "POST|COMMENT|USER", message = "Loại nội dung không hợp lệ") String targetType,
            @NotBlank @Size(max = 100) String targetId,
            @NotBlank @Pattern(regexp = "APPROVE|HIDE|DISMISS", message = "Quyết định không hợp lệ") String decision,
            @Size(max = 256) String reportId) {}

    public record StoryReplyInput(@NotBlank @Size(max = 1000) String content) {}

    @AtLeastOneNotBlank(
            fields = {"content", "imageUrl", "videoUrl"},
            message = "Tin cần có nội dung, hình ảnh hoặc video")
    public record StoryInput(
            @Size(max = 2000) String content,
            @Size(max = 2048) @HttpUrl String imageUrl,
            @Size(max = 2048) @HttpUrl String videoUrl) {}

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

    @GetMapping("/admin/moderation")
    public ApiResponse<?> queue(Authentication a) {
        return ApiResponse.success(service.queue(user(a)));
    }

    @GetMapping("/admin/content/{type}/{id}")
    public ApiResponse<?> reviewContent(
            Authentication a,
            @PathVariable @Pattern(regexp = "(?i)POST|COMMENT|USER", message = "Loại nội dung không hợp lệ") String type,
            @PathVariable @NotBlank(message = "Thiếu mã nội dung") String id) {
        return ApiResponse.success(service.reviewContent(user(a), type, id));
    }

    @PostMapping("/admin/moderation")
    public ApiResponse<?> moderate(Authentication a, @Valid @RequestBody Decision d) {
        service.moderate(user(a), d.targetType(), d.targetId(), d.decision(), d.reportId());
        return ApiResponse.success(null);
    }

    @GetMapping("/saved")
    public ApiResponse<?> saved(Authentication a, @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page) {
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

    @GetMapping("/stories")
    public ApiResponse<?> stories(Authentication a) {
        return ApiResponse.success(service.stories(user(a)));
    }

    @PostMapping("/stories")
    public ApiResponse<?> story(Authentication a, @Valid @RequestBody StoryInput s) {
        return ApiResponse.success(service.story(user(a), s.content(), s.imageUrl(), s.videoUrl()));
    }

    @DeleteMapping("/stories/{id}")
    public ApiResponse<?> deleteStory(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã tin") String id) {
        service.deleteStory(user(a), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/stories/{id}/view")
    public ApiResponse<?> viewStory(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã tin") String id) {
        return ApiResponse.success(java.util.Map.of("views", service.viewStory(user(a), id)));
    }

    @PostMapping("/stories/{id}/replies")
    public ApiResponse<?> replyStory(
            Authentication a,
            @PathVariable @NotBlank(message = "Thiếu mã tin") String id,
            @Valid @RequestBody StoryReplyInput input) {
        return ApiResponse.success(service.replyStory(user(a), id, input.content()));
    }

    @PutMapping("/stories/{id}/reaction")
    public ApiResponse<?> reactStory(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã tin") String id) {
        return ApiResponse.success(java.util.Map.of("liked", service.reactStory(user(a), id, true)));
    }

    @DeleteMapping("/stories/{id}/reaction")
    public ApiResponse<?> removeStoryReaction(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã tin") String id) {
        return ApiResponse.success(java.util.Map.of("liked", service.reactStory(user(a), id, false)));
    }

    @GetMapping("/stories/{id}/viewers")
    public ApiResponse<?> storyViewers(
            Authentication a, @PathVariable @NotBlank(message = "Thiếu mã tin") String id) {
        return ApiResponse.success(service.storyViewers(user(a), id));
    }
}
