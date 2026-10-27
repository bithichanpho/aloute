package com.aloute.posts.web;

import com.aloute.common.response.ApiResponse;
import com.aloute.posts.application.PostModerationRetryService;
import com.aloute.security.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
public class PostModerationRetryController {
    private final PostModerationRetryService retries;
    public PostModerationRetryController(PostModerationRetryService retries) { this.retries = retries; }

    @PostMapping("/{postId}/moderation-retry")
    public ApiResponse<Void> retry(Authentication authentication, @PathVariable String postId) {
        retries.retry(SecurityUtils.currentUserId(authentication), postId);
        return ApiResponse.success("Đã gửi bài viết để kiểm duyệt lại", null);
    }
}
