package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/communities")
@Validated
public class CommunityController {

    private final CommunityService communityService;
    private final AuthRateLimitService rateLimitService;

    public CommunityController(
            CommunityService communityService, AuthRateLimitService rateLimitService) {
        this.communityService = communityService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping
    public ApiResponse<CommunityResponse> create(
            Authentication authentication, @Valid @RequestBody CreateCommunityRequest request) {
        Long userId = requireUserId(authentication);

        rateLimitService.checkAndRecord(
                "CREATE_COMMUNITY",
                String.valueOf(userId),
                10,
                java.time.Duration.ofHours(24),
                java.time.Duration.ofMinutes(60),
                "Bạn đã tạo hội nhóm quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã tạo hội nhóm quá nhiều lần trong 24 giờ. Tạm khóa 60 phút.");

        return ApiResponse.success(
                "Đã tạo hội nhóm", communityService.createCommunity(userId, request));
    }

    @GetMapping("/mine")
    public ApiResponse<List<CommunityResponse>> mine(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getMyCommunities(userId));
    }

    @GetMapping("/discover")
    public ApiResponse<List<CommunityResponse>> discover(
            Authentication authentication, @RequestParam(defaultValue = "30") @Min(value = 1, message = "Giới hạn phải từ 1 trở lên") @Max(value = 50, message = "Giới hạn tối đa 50") int limit) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getDiscoverList(userId, limit));
    }

    @GetMapping("/search")
    public ApiResponse<List<CommunityResponse>> search(
            Authentication authentication,
            @RequestParam("q") @NotBlank(message = "Từ khóa không được để trống") @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String keyword,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "Giới hạn phải từ 1 trở lên") @Max(value = 50, message = "Giới hạn tối đa 50") int limit) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.searchCommunities(keyword, userId, limit));
    }

    @GetMapping("/{communityId}")
    public ApiResponse<CommunityResponse> getCommunity(
            Authentication authentication, @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getCommunity(communityId, userId));
    }

    @PostMapping("/{communityId}/join")
    public ApiResponse<CommunityResponse> join(
            Authentication authentication, @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId) {
        Long userId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "COMMUNITY_JOIN", userId.toString(), 30,
                java.time.Duration.ofHours(1), java.time.Duration.ofMinutes(30),
                "Bạn tham gia hội nhóm quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tham gia hội nhóm do gửi quá nhiều yêu cầu.");
        CommunityResponse result = communityService.join(communityId, userId);
        return ApiResponse.success(
                result.isMember() ? "Đã tham gia hội nhóm" : "Đã gửi yêu cầu tham gia",
                result);
    }

    @PostMapping("/{communityId}/leave")
    public ApiResponse<CommunityResponse> leave(
            Authentication authentication, @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success("Đã rời hội nhóm", communityService.leave(communityId, userId));
    }

    @GetMapping("/{communityId}/requests")
    public ApiResponse<?> pendingMembers(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId) {
        return ApiResponse.success(
                communityService.pendingMembers(communityId, requireUserId(authentication)));
    }

    @PostMapping("/{communityId}/requests/{userId}")
    public ApiResponse<Void> decideMember(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam boolean accept) {
        communityService.decideMember(communityId, requireUserId(authentication), userId, accept);
        return ApiResponse.success(null);
    }

    @PostMapping("/{communityId}/members/{userId}/ban")
    public ApiResponse<Void> banMember(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam(defaultValue = "true") boolean banned) {
        communityService.banMember(communityId, requireUserId(authentication), userId, banned);
        return ApiResponse.success(null);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{communityId}")
    public ApiResponse<CommunityResponse> updateCommunity(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            @Valid @RequestBody CreateCommunityRequest request) {
        return ApiResponse.success(
                communityService.updateCommunity(
                        communityId, requireUserId(authentication), request));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{communityId}")
    public ApiResponse<Void> deleteCommunity(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId) {
        communityService.deleteCommunity(communityId, requireUserId(authentication));
        return ApiResponse.success(null);
    }

    @GetMapping("/{communityId}/posts")
    public ApiResponse<List<PostResponse>> getPosts(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") @Max(value = 100, message = "Trang quá sâu") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "Kích thước trang phải từ 1 trở lên") @Max(value = 50, message = "Kích thước trang tối đa 50") int size) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(communityService.getFeed(communityId, userId, page, size));
    }

    @PostMapping("/{communityId}/posts")
    public ApiResponse<PostResponse> createPost(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            @Valid @RequestBody CreatePostRequest request) {
        Long userId = requireUserId(authentication);

        rateLimitService.checkAndRecord(
                "CREATE_POST",
                String.valueOf(userId),
                25,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(15),
                "Bạn đã đăng bài quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã đăng bài quá nhiều lần trong thời gian ngắn. Tạm khóa đăng bài 15 phút.");

        return ApiResponse.success(
                "Đăng bài thành công", communityService.createPost(communityId, userId, request));
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
