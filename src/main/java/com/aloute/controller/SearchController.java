package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.TrendingTopicDto;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.ClientIpResolver;
import com.aloute.security.SecurityUtils;
import com.aloute.service.DiscoveryService;
import com.aloute.service.PostService;
import com.aloute.service.UserService;
import com.aloute.service.AuthRateLimitService;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/search")
@Validated
public class SearchController {

    private final UserService userService;
    private final PostService postService;
    private final DiscoveryService discoveryService;
    private final AuthRateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public SearchController(
            UserService userService,
            PostService postService,
            DiscoveryService discoveryService,
            AuthRateLimitService rateLimitService,
            ClientIpResolver clientIpResolver) {
        this.userService = userService;
        this.postService = postService;
        this.discoveryService = discoveryService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserProfileDto>> searchUsers(
            Authentication authentication,
            HttpServletRequest request,
            @RequestParam("q") @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String keyword) {

        enforceSearchRateLimit("SEARCH_USERS_IP", request);
        String term = keyword == null ? "" : keyword.trim();
        if (term.isBlank()) return ApiResponse.success(List.of());
        String searchable = term.startsWith("@") ? term.substring(1).trim() : term;
        if (searchable.length() < 2) {
            throw new BadRequestException("Từ khóa tìm người dùng phải có ít nhất 2 ký tự");
        }
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(userService.searchUsers(searchable, currentUserId));
    }

    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> searchPosts(
            Authentication authentication,
            HttpServletRequest request,
            @RequestParam("q") @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String keyword) {

        enforceSearchRateLimit("SEARCH_POSTS_IP", request);
        String term = keyword == null ? "" : keyword.trim();
        if (term.isBlank()) return ApiResponse.success(List.of());
        String searchable = term.startsWith("#") ? term.substring(1).trim() : term;
        if (searchable.length() < 2) {
            throw new BadRequestException("Từ khóa tìm bài viết phải có ít nhất 2 ký tự");
        }
        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.searchPosts(term, currentUserId, 20));
    }

    private void enforceSearchRateLimit(String namespace, HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        rateLimitService.checkAndRecord(
                namespace,
                ip,
                60,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                "Bạn tìm kiếm quá thường xuyên. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã vượt quá 60 lượt tìm kiếm trong 15 phút. Vui lòng thử lại sau.");
    }

    @GetMapping("/suggestions")
    public ApiResponse<List<UserProfileDto>> suggestions(
            Authentication authentication,
            @RequestParam(name = "limit", defaultValue = "8") @Min(value = 1, message = "Giới hạn phải từ 1 trở lên") @Max(value = 50, message = "Giới hạn tối đa 50") int limit) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(discoveryService.getSuggestedUsers(currentUserId, limit));
    }

    @GetMapping("/trending")
    public ApiResponse<List<TrendingTopicDto>> trending(
            @RequestParam(name = "limit", defaultValue = "8") @Min(value = 1, message = "Giới hạn phải từ 1 trở lên") @Max(value = 50, message = "Giới hạn tối đa 50") int limit) {

        return ApiResponse.success(discoveryService.getTrendingTopics(limit));
    }
}
