package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.TrendingTopicDto;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.DiscoveryService;
import com.aloute.service.PostService;
import com.aloute.service.UserService;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {

    private final UserService userService;
    private final PostService postService;
    private final DiscoveryService discoveryService;

    public SearchController(
            UserService userService, PostService postService, DiscoveryService discoveryService) {
        this.userService = userService;
        this.postService = postService;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserProfileDto>> searchUsers(
            Authentication authentication, @RequestParam("q") @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String keyword) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        if (keyword == null || keyword.isBlank()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(userService.searchUsers(keyword.trim(), currentUserId));
    }

    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> searchPosts(
            Authentication authentication, @RequestParam("q") @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String keyword) {

        Long currentUserId = SecurityUtils.currentUserId(authentication);
        return ApiResponse.success(postService.searchPosts(keyword, currentUserId, 20));
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
