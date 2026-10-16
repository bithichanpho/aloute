package com.aloute.controller;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.beans.factory.annotation.Value;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.request.ReactionRequest;
import com.aloute.dto.request.RepostRequest;
import com.aloute.dto.request.SharePostRequest;
import com.aloute.service.ChatService;
import com.aloute.service.ContentAccessService;
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
    private final ChatService chatService;
    private final ContentAccessService contentAccessService;
    private final String frontendBaseUrl;

    public PostController(
            PostService postService,
            LikeService likeService,
            FriendService friendService,
            AuthRateLimitService rateLimitService,
            ChatService chatService,
            ContentAccessService contentAccessService,
            @Value("${app.mail.frontend-base-url:http://localhost:8080}") String frontendBaseUrl) {
        this.postService = postService;
        this.likeService = likeService;
        this.friendService = friendService;
        this.rateLimitService = rateLimitService;
        this.chatService = chatService;
        this.contentAccessService = contentAccessService;
        this.frontendBaseUrl = frontendBaseUrl;
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

    /** Sends a post link to selected mutual friends through the existing chat system. */
    @PostMapping("/{postId}/share")
    public ApiResponse<Map<String, Object>> sharePost(
            Authentication authentication,
            @PathVariable @NotBlank(message = "Thiếu mã bài viết") String postId,
            @Valid @RequestBody SharePostRequest request) {
        Long senderId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "SHARE_POST", senderId.toString(), 30,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn chia sẻ quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa chia sẻ do gửi quá nhiều yêu cầu.");

        var post = contentAccessService.requirePost(postId, senderId);
        Set<Long> recipients = new LinkedHashSet<>(request.recipientIds());
        if (recipients.contains(senderId)) {
            throw new com.aloute.common.exception.BadRequestException(
                    "Bạn không thể chia sẻ bài viết cho chính mình");
        }

        // Validate every recipient and post visibility before sending anything, avoiding
        // partial delivery caused by a recipient who is not allowed to see the post.
        for (Long recipientId : recipients) {
            if (!friendService.isFriend(senderId, recipientId)) {
                throw new com.aloute.common.exception.BadRequestException(
                        "Bạn chỉ có thể gửi bài viết trực tiếp cho bạn bè đang theo dõi lẫn nhau");
            }
            contentAccessService.requirePost(post.getId(), recipientId);
        }

        String postUrl = frontendBaseUrl.replaceAll("/+$", "")
                + "/feed?postId=" + java.net.URLEncoder.encode(post.getId(), java.nio.charset.StandardCharsets.UTF_8)
                + "#post-" + post.getId();
        String note = request.message() == null ? "" : request.message().trim();
        String content = (note.isEmpty() ? "" : note + "\n\n")
                + "📎 Mình chia sẻ một bài viết với bạn:\n" + postUrl;

        for (Long recipientId : recipients) {
            chatService.sendMessage(senderId, recipientId, content, "share-post-" + UUID.randomUUID());
        }
        return ApiResponse.success(
                "Đã gửi bài viết cho " + recipients.size() + " người bạn",
                Map.of("sentCount", recipients.size()));
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
