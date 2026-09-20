package com.aloute.controller;

import java.util.List;
import java.util.Map;

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
import com.aloute.dto.request.ReactionRequest;
import com.aloute.dto.request.RepostRequest;
import com.aloute.dto.response.LikeResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.ReactionResponse;
import com.aloute.dto.response.RepostResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AuthRateLimitService;
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
    private final AuthRateLimitService rateLimitService;

    public PostController(PostService postService, LikeService likeService, FriendService friendService,
            AuthRateLimitService rateLimitService) {
        this.postService = postService;
        this.likeService = likeService;
        this.friendService = friendService;
        this.rateLimitService = rateLimitService;
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

        if (currentUserId == null) {
            throw new UnauthorizedException("Cần đăng nhập để xem feed");
        }
        return ApiResponse.success(postService.getFeed(currentUserId, friendService.getFollowingIds(currentUserId), page, size));
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostResponse> getPost(Authentication authentication, @PathVariable String postId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.getPost(postId, currentUserId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<PostResponse>> getPostsByUser(
            Authentication authentication, @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.getPostsByUser(userId, currentUserId, page, size));
    }

    @PostMapping
    public ApiResponse<PostResponse> createPost(
            Authentication authentication, @Valid @RequestBody CreatePostRequest request) {
        Long currentUserId = requireUserId(authentication);

        // Chống spam đăng bài hàng loạt: tối đa 20 bài / 10 phút mỗi user.
        rateLimitService.checkAndRecord("CREATE_POST", String.valueOf(currentUserId),
                20, java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn đã đăng bài quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã đăng bài quá nhiều lần trong thời gian ngắn. Tạm khóa đăng bài 10 phút.");

        return ApiResponse.success("Đăng bài thành công", postService.createPost(currentUserId, request));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<Void> deletePost(Authentication authentication, @PathVariable String postId) {
        Long currentUserId = requireUserId(authentication);
        postService.deletePost(postId, currentUserId);
        return ApiResponse.success("Đã xóa bài viết", null);
    }

    /**
     * Đặt trạng thái like mong muốn. Front-end có thể bấm liên tục mà không phải
     * chờ request trước hoàn tất; backend xử lý idempotent và trả cả likeCount.
     * (Giữ nguyên để tương thích với client cũ — client mới nên dùng /react bên dưới.)
     */
    @PostMapping("/{postId}/like")
    public ApiResponse<LikeResponse> setLike(
            Authentication authentication, @PathVariable String postId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long currentUserId = requireUserId(authentication);
        boolean liked = true;
        if (body != null && body.get("liked") != null) {
            liked = Boolean.parseBoolean(String.valueOf(body.get("liked")));
        }
        return ApiResponse.success(likeService.setLike(postId, currentUserId, liked));
    }

    /**
     * Đặt reaction đa emoji (LIKE/LOVE/HAHA/WOW/SAD/ANGRY) cho bài viết.
     * type = null hoặc rỗng nghĩa là bỏ reaction hiện tại.
     */
    @PostMapping("/{postId}/react")
    public ApiResponse<ReactionResponse> setReaction(
            Authentication authentication, @PathVariable String postId,
            @RequestBody(required = false) ReactionRequest request) {
        Long currentUserId = requireUserId(authentication);
        String type = request != null ? request.getType() : null;
        return ApiResponse.success(likeService.setReaction(postId, currentUserId, type));
    }

    /** Toggle đăng lại (repost) 1 bài viết — bấm lần 2 sẽ bỏ đăng lại. */
    @PostMapping("/{postId}/repost")
    public ApiResponse<RepostResponse> repost(
            Authentication authentication, @PathVariable String postId,
            @RequestBody(required = false) RepostRequest request) {
        Long currentUserId = requireUserId(authentication);

        // Chống spam đăng lại hàng loạt: tối đa 15 lần / 10 phút mỗi user.
        rateLimitService.checkAndRecord("REPOST", String.valueOf(currentUserId),
                15, java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn đã đăng lại quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã đăng lại quá nhiều lần trong thời gian ngắn. Tạm khóa 10 phút.");

        return ApiResponse.success(postService.repost(currentUserId, postId, request));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}