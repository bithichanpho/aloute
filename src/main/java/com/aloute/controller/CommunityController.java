package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.CreateCommunityRequest;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.CommunityResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.CommunityService;

import jakarta.validation.Valid;

/**
 * REST API Hội nhóm. Các trang HTML (/communities, /communities/{id}) và fragment tải thêm bài
 * nằm ở {@link ViewController}; controller này chỉ lo các thao tác dữ liệu.
 */
@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityService communityService;
    private final AuthRateLimitService rateLimitService;

    public CommunityController(CommunityService communityService, AuthRateLimitService rateLimitService) {
        this.communityService = communityService;
        this.rateLimitService = rateLimitService;
    }

    /** POST /api/communities — tạo hội nhóm mới. */
    @PostMapping
    public ApiResponse<CommunityResponse> create(
            Authentication authentication, @Valid @RequestBody CreateCommunityRequest request) {
        Long userId = requireUserId(authentication);

        // Chống spam tạo hội nhóm hàng loạt: tối đa 5 nhóm / 10 phút mỗi user.
        rateLimitService.checkAndRecord("CREATE_COMMUNITY", String.valueOf(userId),
                5, java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn đã tạo hội nhóm quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã tạo hội nhóm quá nhiều lần trong thời gian ngắn. Tạm khóa 10 phút.");

        return ApiResponse.success("Đã tạo hội nhóm", communityService.createCommunity(userId, request));
    }

    /** GET /api/communities/mine — hội nhóm tôi đã tham gia. */
    @GetMapping("/mine")
    public ApiResponse<List<CommunityResponse>> mine(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getMyCommunities(userId));
    }

    /** GET /api/communities/discover?limit=30 — hội nhóm công khai đông thành viên nhất. */
    @GetMapping("/discover")
    public ApiResponse<List<CommunityResponse>> discover(
            Authentication authentication, @RequestParam(defaultValue = "30") int limit) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getDiscoverList(userId, limit));
    }

    /** GET /api/communities/search?q=keyword — tìm hội nhóm công khai theo tên. */
    @GetMapping("/search")
    public ApiResponse<List<CommunityResponse>> search(
            Authentication authentication, @RequestParam("q") String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.searchCommunities(keyword, userId, limit));
    }

    @GetMapping("/{communityId}")
    public ApiResponse<CommunityResponse> getCommunity(
            Authentication authentication, @PathVariable Long communityId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getCommunity(communityId, userId));
    }

    @PostMapping("/{communityId}/join")
    public ApiResponse<CommunityResponse> join(Authentication authentication, @PathVariable Long communityId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success("Đã tham gia hội nhóm", communityService.join(communityId, userId));
    }

    /** data = null nếu hội nhóm đã bị xóa vì không còn thành viên nào. */
    @PostMapping("/{communityId}/leave")
    public ApiResponse<CommunityResponse> leave(Authentication authentication, @PathVariable Long communityId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success("Đã rời hội nhóm", communityService.leave(communityId, userId));
    }

    /** GET /api/communities/{id}/posts?page=0&size=20 — dạng JSON (dạng HTML fragment nằm ở ViewController). */
    @GetMapping("/{communityId}/posts")
    public ApiResponse<List<PostResponse>> getPosts(
            Authentication authentication, @PathVariable Long communityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getFeed(communityId, userId, page, size));
    }

    /** POST /api/communities/{id}/posts — đăng bài trong hội nhóm (chỉ thành viên). */
    @PostMapping("/{communityId}/posts")
    public ApiResponse<PostResponse> createPost(
            Authentication authentication, @PathVariable Long communityId,
            @Valid @RequestBody CreatePostRequest request) {
        Long userId = requireUserId(authentication);

        // Dùng chung bộ đếm CREATE_POST với PostController: tổng bài đăng (feed + hội nhóm) tối đa 20 / 10 phút.
        rateLimitService.checkAndRecord("CREATE_POST", String.valueOf(userId),
                20, java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10),
                "Bạn đã đăng bài quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã đăng bài quá nhiều lần trong thời gian ngắn. Tạm khóa đăng bài 10 phút.");

        return ApiResponse.success("Đăng bài thành công", communityService.createPost(communityId, userId, request));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}