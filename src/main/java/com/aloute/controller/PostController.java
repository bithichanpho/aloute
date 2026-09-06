package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.PostResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.FriendService;
import com.aloute.service.LikeService;
import com.aloute.service.PostService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;
    private final LikeService likeService;
    private final FriendService friendService;

    public PostController(PostService postService, LikeService likeService, FriendService friendService) {
        this.postService = postService;
        this.likeService = likeService;
        this.friendService = friendService;
    }

    /**
     * GET /api/posts/feed?tab=foryou|following&page=0&size=10
     * "foryou": toàn bộ bài viết mới nhất (mặc định).
     * "following": chỉ bài viết của những người mình đang follow.
     */
    @GetMapping("/feed")
    public ApiResponse<List<PostResponse>> getFeed(
            Authentication authentication,
            @RequestParam(defaultValue = "foryou") String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);

        if ("following".equalsIgnoreCase(tab)) {
            if (currentUserId == null) {
                throw new UnauthorizedException("Cần đăng nhập để xem tab Following");
            }
            List<Long> followingIds = friendService.getFollowingIds(currentUserId);
            return ApiResponse.success(postService.getFeedByFollowing(currentUserId, followingIds, page, size));
        }

        return ApiResponse.success(postService.getFeed(currentUserId, page, size));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<PostResponse>> getPostsByUser(
            Authentication authentication, @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.getPostsByUser(userId, currentUserId));
    }

    @PostMapping
    public ApiResponse<PostResponse> createPost(
            Authentication authentication, @Valid @RequestBody CreatePostRequest request) {
        Long currentUserId = requireUserId(authentication);
        return ApiResponse.success("Đăng bài thành công", postService.createPost(currentUserId, request));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<Void> deletePost(Authentication authentication, @PathVariable String postId) {
        Long currentUserId = requireUserId(authentication);
        postService.deletePost(postId, currentUserId);
        return ApiResponse.success("Đã xóa bài viết", null);
    }

    /** Bật/tắt like — trả về trạng thái mới (đã like hay chưa) để JS cập nhật UI. */
    @PostMapping("/{postId}/like")
    public ApiResponse<Boolean> toggleLike(Authentication authentication, @PathVariable String postId) {
        Long currentUserId = requireUserId(authentication);
        return ApiResponse.success(likeService.toggleLike(postId, currentUserId));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}