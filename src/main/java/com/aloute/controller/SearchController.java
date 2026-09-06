package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.UserService;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final UserService userService;

    public SearchController(UserService userService) {
        this.userService = userService;
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
}