package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.CommentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class CommentController {
    public record CommentReactionRequest(
            @jakarta.validation.constraints.Pattern(
                    regexp = "(?i)LIKE|LOVE|HAHA|WOW|SAD|ANGRY|",
                    message = "Cảm xúc không hợp lệ") String type) {}

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<List<CommentResponse>> getComments(
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(commentService.getComments(postId, page, size));
    }

    @PostMapping("/posts/{postId}/comments")
    public ApiResponse<CommentResponse> createComment(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @Valid @RequestBody CreateCommentRequest request) {

        Long currentUserId = requireUserId(authentication);
        return ApiResponse.success(
                "Đã bình luận", commentService.createComment(postId, currentUserId, request));
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bình luận") String commentId) {
        Long currentUserId = requireUserId(authentication);
        commentService.deleteComment(commentId, currentUserId);
        return ApiResponse.success("Đã xóa bình luận", null);
    }

    @PutMapping("/comments/{commentId}")
    public ApiResponse<CommentResponse> editComment(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bình luận") String commentId,
            @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.success(
                commentService.editComment(
                        commentId, requireUserId(authentication), request.getContent()));
    }

    @PutMapping("/comments/{commentId}/reaction")
    public ApiResponse<?> react(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bình luận") String commentId,
            @Valid @RequestBody CommentReactionRequest request) {
        return ApiResponse.success(
                commentService.react(commentId, requireUserId(authentication), request.type()));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
