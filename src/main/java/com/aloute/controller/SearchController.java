package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.TrendingTopicDto;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.DiscoveryService;
import com.aloute.service.PostService;
import com.aloute.service.UserService;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final UserService userService;
    private final PostService postService;
    private final DiscoveryService discoveryService;

    public SearchController(UserService userService, PostService postService, DiscoveryService discoveryService) {
        this.userService = userService;
        this.postService = postService;
        this.discoveryService = discoveryService;
    }

    /** Tìm người dùng theo username hoặc họ tên. */
    @GetMapping("/users")
    public ApiResponse<List<UserProfileDto>> searchUsers(
            Authentication authentication, @RequestParam("q") String keyword) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        if (keyword == null || keyword.isBlank()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(userService.searchUsers(keyword.trim(), currentUserId));
    }

    /** Tìm bài viết theo nội dung — dùng cho tab "Bài viết" của trang tìm kiếm. */
    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> searchPosts(
            Authentication authentication, @RequestParam("q") String keyword) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.searchPosts(keyword, currentUserId, 20));
    }

    /** Danh sách "Có thể bạn quen" hiển thị khi ô tìm kiếm còn trống. */
    @GetMapping("/suggestions")
    public ApiResponse<List<UserProfileDto>> suggestions(
            Authentication authentication,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(discoveryService.getSuggestedUsers(currentUserId, limit));
    }

    /** Các chủ đề đang được nói tới nhiều nhất. */
    @GetMapping("/trending")
    public ApiResponse<List<TrendingTopicDto>> trending(
            @RequestParam(name = "limit", defaultValue = "8") int limit) {

        return ApiResponse.success(discoveryService.getTrendingTopics(limit));
    }
}
