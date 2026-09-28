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
import org.springframework.validation.annotation.Validated;

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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/posts")
@Validated
public class PostController {

    private final PostService postService;
    private final LikeService likeService;
    private final FriendService friendService;
    private final AuthRateLimitService rateLimitService;

    public PostController(
            PostService postService,
            LikeService likeService,
            FriendService friendService,
            AuthRateLimitService rateLimitService) {
        this.postService = postService;
        this.likeService = likeService;
        this.friendService = friendService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/feed")
    public ApiResponse<List<PostResponse>> getFeed(
            Authentication authentication,
            @RequestParam(defaultValue = "foryou") @Pattern(regexp = "(?i)foryou|following", message = "Tab feed không hợp lệ") String tab,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "Kích thước trang phải từ 1 trở lên") @Max(value = 50, message = "Kích thước trang tối đa 50") int size) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);

        if ("following".equalsIgnoreCase(tab)) {
            if (currentUserId == null) {
                throw new UnauthorizedException("Cần đăng nhập để xem tab Following");
            }
            List<Long> followingIds = friendService.getFollowingIds(currentUserId);
            return ApiResponse.success(
                    postService.getFeedByFollowing(currentUserId, followingIds, page, size));
        }

        if (currentUserId == null) {
            throw new UnauthorizedException("Cần đăng nhập để xem feed");
        }
        return ApiResponse.success(
                postService.getFeed(
                        currentUserId, friendService.getFollowingIds(currentUserId), page, size));
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostResponse> getPost(
            Authentication authentication, @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.getPost(postId, currentUserId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<PostResponse>> getPostsByUser(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "Kích thước trang phải từ 1 trở lên") @Max(value = 50, message = "Kích thước trang tối đa 50") int size) {
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.getPostsByUser(userId, currentUserId, page, size));
    }

    @PostMapping
    public ApiResponse<PostResponse> createPost(
            Authentication authentication, @Valid @RequestBody CreatePostRequest request) {
        Long currentUserId = requireUserId(authentication);

        rateLimitService.checkAndRecord(
                "CREATE_POST",
                String.valueOf(currentUserId),
                25,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(15),
                "Bạn đã đăng bài quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã đăng bài quá nhiều lần trong thời gian ngắn. Tạm khóa đăng bài 15 phút.");

        return ApiResponse.success(
                "Đăng bài thành công", postService.createPost(currentUserId, request));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<Void> deletePost(
            Authentication authentication, @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId) {
        Long currentUserId = requireUserId(authentication);
        postService.deletePost(postId, currentUserId);
        return ApiResponse.success("Đã xóa bài viết", null);
    }

    @PostMapping("/{postId}/like")
    public ApiResponse<LikeResponse> setLike(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long currentUserId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "LIKE", currentUserId.toString(), 120,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(5),
                "Bạn thao tác quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tương tác do gửi quá nhiều yêu cầu.");
        boolean liked = true;
        if (body != null && body.get("liked") != null) {
            liked = Boolean.parseBoolean(String.valueOf(body.get("liked")));
        }
        return ApiResponse.success(likeService.setLike(postId, currentUserId, liked));
    }

    @PostMapping("/{postId}/react")
    public ApiResponse<ReactionResponse> setReaction(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @Valid @RequestBody ReactionRequest request) {
        Long currentUserId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "REACTION", currentUserId.toString(), 120,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(5),
                "Bạn thao tác quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tương tác do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(likeService.setReaction(postId, currentUserId, request.getType()));
    }

    @PostMapping("/{postId}/repost")
    public ApiResponse<RepostResponse> repost(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @RequestBody(required = false) RepostRequest request) {
        Long currentUserId = requireUserId(authentication);

        rateLimitService.checkAndRecord(
                "REPOST",
                String.valueOf(currentUserId),
                15,
                java.time.Duration.ofMinutes(10),
                java.time.Duration.ofMinutes(10),
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
