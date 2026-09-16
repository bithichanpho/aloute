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
import com.aloute.service.GroupService;
import com.aloute.service.PresenceService;
import com.aloute.service.DiscoveryService;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.User;

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
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final DiscoveryService discoveryService;

    public ViewController(
            PostService postService,
            UserService userService,
            FriendService friendService,
            NotificationService notificationService,
            ChatService chatService,
            GroupService groupService,
            PresenceService presenceService,
            DiscoveryService discoveryService) {
        this.postService = postService;
        this.userService = userService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.groupService = groupService;
        this.presenceService = presenceService;
        this.discoveryService = discoveryService;
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

        // Dữ liệu cho rail bên phải của feed: nhóm đang tham gia + bạn bè đang online.
        // Đây chỉ là dữ liệu hiển thị, không thay đổi logic feed hiện tại.
        List<ChatGroup> feedGroups = groupService.getMyGroups(userId);

        List<User> activeFriends = friendService.getMutualFriendIds(userId).stream()
                .map(userService::findUserBasicInfo)
                .flatMap(java.util.Optional::stream)
                .filter(user -> presenceService.isOnline(user.getId()))
                .limit(8)
                .collect(java.util.stream.Collectors.toList());

        // Gợi ý kết bạn: dùng chung với trang /search (xem DiscoveryService).
        // Trước đây khối này nạp toàn bộ bảng users rồi dựng hồ sơ đầy đủ cho từng người.
        model.addAttribute("feedGroups", feedGroups.stream().limit(8).collect(java.util.stream.Collectors.toList()));
        model.addAttribute("activeFriends", activeFriends);
        model.addAttribute("suggestedUsers", discoveryService.getSuggestedUsers(userId, 6));

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
    public String chat(Authentication authentication, Model model,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long userId) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";

        List<ConversationResponse> conversations = chatService.getConversations(currentUserId);
        model.addAttribute("conversations", conversations);
        // Được điều hướng tới từ nút "Nhắn tin" trên trang cá nhân -> mở sẵn đúng hội thoại đó
        model.addAttribute("openUserId", userId);
        addCommonAttributes(model, currentUserId, "chat");
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

    @GetMapping("/api/posts/feed-fragment")
    public String feedFragment(Authentication authentication, Model model,
            @RequestParam(defaultValue = "foryou") String tab,
            @RequestParam(defaultValue = "0") int page) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        model.addAttribute("posts", "following".equalsIgnoreCase(tab)
                ? postService.getFeedByFollowing(userId, friendService.getFollowingIds(userId), page, 20)
                : postService.getFeed(userId, page, 20));
        var currentUser = new UserProfileDto();
        currentUser.setId(userId);
        model.addAttribute("currentUser", currentUser);
        return "feed/index :: .post";
    }

    @GetMapping("/api/posts/user/{userId}/fragment")
    public String profileFragment(Authentication authentication, Model model, @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";
        model.addAttribute("userPosts", postService.getPostsByUser(userId, currentUserId, page, 20));
        model.addAttribute("isOwnProfile", currentUserId.equals(userId));
        return "profile/view :: .post";
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
        model.addAttribute("postCount", postService.countPostsByUser(profileUserId));
        model.addAttribute("isOwnProfile", currentUserId != null && currentUserId.equals(profileUserId));
        if (currentUserId != null) {
            addCommonAttributes(model, currentUserId, "profile");
        }
        return "profile/view";
    }

    private void addCommonAttributes(Model model, Long currentUserId, String activeNav) {
        model.addAttribute("activeNav", activeNav);
        if (currentUserId == null) return;
        model.addAttribute("currentUser", userService.getLiteProfile(currentUserId));
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