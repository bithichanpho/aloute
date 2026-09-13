package com.aloute.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.aloute.dto.response.ConversationResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ChatService;
import com.aloute.service.FriendService;
import com.aloute.service.NotificationService;
import com.aloute.service.PostService;
import com.aloute.service.UserService;

/**
 * ViewController — trả về tên template Thymeleaf tương ứng cho từng URL, kèm
 * dữ liệu thật đổ vào model (không còn dữ liệu mẫu tĩnh trong .html nữa).
 *
 * Vì ứng dụng dùng JWT (không session), "đăng nhập hay chưa" được xác định
 * bởi JwtAuthFilter chạy TRƯỚC controller này (đọc cookie ALOUTE_TOKEN) và set
 * sẵn Authentication vào SecurityContext — ở đây chỉ cần kiểm tra Authentication
 * có null hay không để quyết định render trang hay redirect về /login.
 */
@Controller
public class ViewController {

    private final PostService postService;
    private final UserService userService;
    private final FriendService friendService;
    private final NotificationService notificationService;
    private final ChatService chatService;

    public ViewController(
            PostService postService,
            UserService userService,
            FriendService friendService,
            NotificationService notificationService,
            ChatService chatService) {
        this.postService = postService;
        this.userService = userService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.chatService = chatService;
    }

    @GetMapping("/")
    public String home(Authentication authentication) {
        return isLoggedIn(authentication) ? "redirect:/feed" : "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        return isLoggedIn(authentication) ? "redirect:/feed" : "auth/login";
    }

    @GetMapping("/register")
    public String register(Authentication authentication) {
        return isLoggedIn(authentication) ? "redirect:/feed" : "auth/register";
    }

    /** Trang xác nhận sau khi bấm link trong email — verify thật sự do JS gọi /api/auth/verify-email. */
    @GetMapping("/verify-email")
    public String verifyEmailPage(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        return "auth/verify";
    }

    /** Trang nhập email để yêu cầu link đặt lại mật khẩu. */
    @GetMapping("/forgot-password")
    public String forgotPassword(Authentication authentication) {
        return isLoggedIn(authentication) ? "redirect:/feed" : "auth/forgot-password";
    }

    /** Trang nhập mật khẩu mới, mở từ link trong email quên mật khẩu. */
    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @GetMapping("/feed")
    public String feed(
            Authentication authentication, Model model,
            @RequestParam(defaultValue = "foryou") String tab) {

        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        List<PostResponse> posts = "following".equalsIgnoreCase(tab)
                ? postService.getFeedByFollowing(userId, friendService.getFollowingIds(userId), 0, 20)
                : postService.getFeed(userId, 0, 20);

        model.addAttribute("posts", posts);
        model.addAttribute("activeTab", tab);
        addCommonAttributes(model, userId, "feed");
        return "feed/index";
    }

    @GetMapping("/compose")
    public String compose(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        addCommonAttributes(model, userId, "compose");
        return "feed/compose";
    }

    @GetMapping("/chat")
    public String chat(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        List<ConversationResponse> conversations = chatService.getConversations(userId);
        model.addAttribute("conversations", conversations);
        addCommonAttributes(model, userId, "chat");
        return "chat/index";
    }

    @GetMapping("/profile/me")
    public String myProfile(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        return renderProfile(userId, userId, model);
    }

    @GetMapping("/profile/{userId}")
    public String userProfile(Authentication authentication, @PathVariable Long userId, Model model) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";
        return renderProfile(userId, currentUserId, model);
    }

    @GetMapping("/profile/edit")
    public String profileEdit(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        model.addAttribute("profileUser", userService.getProfile(userId, userId));
        addCommonAttributes(model, userId, "profile");
        return "profile/edit";
    }

    @GetMapping("/search")
    public String search(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        addCommonAttributes(model, userId, "search");
        return "search/index";
    }

    @GetMapping("/activity")
    public String activity(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        model.addAttribute("notifications", notificationService.getMyNotifications(userId));
        addCommonAttributes(model, userId, "activity");
        return "activity/index";
    }

    @GetMapping("/saved")
    public String saved(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        addCommonAttributes(model, userId, "saved");
        model.addAttribute("pageTitle", "Đã lưu");
        return "placeholder/index";
    }

    @GetMapping("/insights")
    public String insights(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        addCommonAttributes(model, userId, "insights");
        model.addAttribute("pageTitle", "Thông tin chi tiết");
        return "placeholder/index";
    }

    // ===== Helpers =====

    private String renderProfile(Long profileUserId, Long currentUserId, Model model) {
        UserProfileDto profile = userService.getProfile(profileUserId, currentUserId);
        model.addAttribute("profileUser", profile);
        model.addAttribute("userPosts", postService.getPostsByUser(profileUserId, currentUserId));
        model.addAttribute("isOwnProfile", currentUserId != null && currentUserId.equals(profileUserId));
        if (currentUserId != null) {
            addCommonAttributes(model, currentUserId, "profile");
        }
        return "profile/view";
    }

    private void addCommonAttributes(Model model, Long currentUserId, String activeNav) {
        model.addAttribute("activeNav", activeNav);
        if (currentUserId == null) return;
        model.addAttribute("currentUser", userService.getProfile(currentUserId, currentUserId));
        model.addAttribute("unreadCount", notificationService.countUnread(currentUserId));
        model.addAttribute("unreadMessageCount", chatService.countUnreadTotal(currentUserId));
    }

    private boolean isLoggedIn(Authentication authentication) {
        return SecurityUtils.currentUserId(authentication) != null;
    }

    private Long requireLogin(Authentication authentication) {
        return SecurityUtils.currentUserId(authentication);
    }
}