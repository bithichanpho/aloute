package com.aloute.controller;

import java.util.Collections;
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
import com.aloute.service.CommunityService;
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
    private final CommunityService communityService;

    public ViewController(
            PostService postService,
            UserService userService,
            FriendService friendService,
            NotificationService notificationService,
            ChatService chatService,
            GroupService groupService,
            PresenceService presenceService,
            DiscoveryService discoveryService,
            CommunityService communityService) {
        this.postService = postService;
        this.userService = userService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.groupService = groupService;
        this.presenceService = presenceService;
        this.discoveryService = discoveryService;
        this.communityService = communityService;
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

        List<Long> followingIds = friendService.getFollowingIds(userId);
        List<PostResponse> posts = "following".equalsIgnoreCase(tab)
                ? postService.getFeedByFollowing(userId, followingIds, 0, 20)
                : postService.getFeed(userId, followingIds, 0, 20);

        model.addAttribute("posts", posts);
        model.addAttribute("activeTab", tab);

        // Dữ liệu cho rail bên phải của feed: nhóm đang tham gia + bạn bè đang online.
        // Đây chỉ là dữ liệu hiển thị, không thay đổi logic feed hiện tại.
        List<ChatGroup> feedGroups = groupService.getMyGroups(userId);

        // Nạp TOÀN BỘ bạn bè chung (mutual friend) bằng 1 query duy nhất, rồi lọc "đang online"
        // hoàn toàn trong bộ nhớ (PresenceService chỉ là 1 Map, không đụng DB).
        // Bản trước gọi presenceService.isOnline(Long) cho từng bạn — hàm đó bên trong lại tự
        // query DB để đổi userId -> username, tức là tốn thêm N query chỉ để tra 1 Map trong RAM.
        List<User> mutualFriends = userService.findUsersByIds(friendService.getMutualFriendIds(userId));
        List<User> activeFriends = mutualFriends.stream()
                .filter(friend -> presenceService.isOnlineUsername(friend.getUsername()))
                .limit(8)
                .collect(java.util.stream.Collectors.toList());

        // Gợi ý kết bạn: dùng chung với trang /search (xem DiscoveryService).
        // Truyền sẵn feedGroups vừa tải ở trên để DiscoveryService không phải tự
        // query lại danh sách nhóm lần thứ 2 trong cùng 1 request /feed.
        model.addAttribute("feedGroups", feedGroups.stream().limit(8).collect(java.util.stream.Collectors.toList()));
        model.addAttribute("activeFriends", activeFriends);
        model.addAttribute("suggestedUsers", discoveryService.getSuggestedUsers(userId, 6, feedGroups));

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

        // userId != null: đến từ nút "Nhắn tin" trên trang cá nhân — đảm bảo luôn có
        // sẵn ô hội thoại với người đó kể cả khi hai bên chưa từng nhắn/chưa là bạn bè.
        List<ConversationResponse> conversations = chatService.getConversations(currentUserId, userId);
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
                : postService.getFeed(userId, friendService.getFollowingIds(userId), page, 20));
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
        List<PostResponse> userPosts = postService.getPostsByUser(userId, currentUserId, page, 20);
        model.addAttribute("userPosts", userPosts);
        // profile/view được dùng làm template cho fragment .post nên Thymeleaf vẫn
        // có thể đánh giá các biểu thức của tab Đăng lại. Phải luôn cung cấp list
        // rỗng thay vì để biến null/missing gây TemplateProcessingException.
        model.addAttribute("repostPosts", Collections.emptyList());
        model.addAttribute("mediaPosts", userPosts.stream()
                .filter(post -> post.getImageUrl() != null && !post.getImageUrl().isBlank())
                .collect(java.util.stream.Collectors.toList()));
        model.addAttribute("isOwnProfile", currentUserId.equals(userId));
        return "profile/view :: profileTimelinePost";
    }

    @GetMapping("/api/posts/user/{userId}/reposts/fragment")
    public String profileRepostFragment(Authentication authentication, Model model, @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";

        List<PostResponse> repostPosts = postService.getRepostsByUser(userId, currentUserId, page, 20);
        // Cung cấp đủ model dùng trong profile/view để fragment không phụ thuộc vào
        // thứ tự Thymeleaf xử lý các node ngoài phần .post.
        model.addAttribute("repostPosts", repostPosts);
        model.addAttribute("userPosts", Collections.emptyList());
        model.addAttribute("mediaPosts", Collections.emptyList());
        model.addAttribute("isOwnProfile", currentUserId.equals(userId));
        return "profile/view :: profileRepostPost";
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

    @GetMapping("/communities")
    public String communities(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        model.addAttribute("myCommunities", communityService.getMyCommunities(userId));
        model.addAttribute("discoverCommunities", communityService.getDiscoverList(userId, 30));
        addCommonAttributes(model, userId, "communities");
        return "community/index";
    }

    @GetMapping("/communities/{communityId}")
    public String communityDetail(Authentication authentication, @PathVariable Long communityId, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        model.addAttribute("community", communityService.getCommunity(communityId, userId));
        // Template community/view.html dùng biến này ở cả nhánh mở và khóa.
        // Phải luôn có giá trị mặc định để SpringEL không ném exception khi user
        // là thành viên của hội nhóm công khai.
        model.addAttribute("feedLocked", false);
        try {
            model.addAttribute("posts", communityService.getFeed(communityId, userId, 0, 20));
        } catch (com.aloute.common.exception.UnauthorizedException e) {
            // Hội nhóm riêng tư và user chưa tham gia -> không xem được bài viết, chỉ xem thông tin chung.
            model.addAttribute("posts", java.util.Collections.emptyList());
            model.addAttribute("feedLocked", true);
        }
        addCommonAttributes(model, userId, "communities");
        return "community/view";
    }

    @GetMapping("/api/communities/{communityId}/posts/fragment")
    public String communityFeedFragment(Authentication authentication, @PathVariable Long communityId, Model model,
            @RequestParam(defaultValue = "0") int page) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        model.addAttribute("posts", communityService.getFeed(communityId, userId, page, 20));
        var currentUser = new UserProfileDto();
        currentUser.setId(userId);
        model.addAttribute("currentUser", currentUser);
        return "feed/index :: .post";
    }

    // ===== Helpers =====

    private String renderProfile(Long profileUserId, Long currentUserId, Model model) {
        UserProfileDto profile = userService.getProfile(profileUserId, currentUserId);
        List<PostResponse> userPosts = postService.getPostsByUser(profileUserId, currentUserId);

        model.addAttribute("profileUser", profile);
        model.addAttribute("userPosts", userPosts);
        // Lưới chỉ hiển thị bài CÓ ẢNH (bài chỉ có chữ nằm ở tab Dòng thời gian).
        model.addAttribute("mediaPosts", userPosts.stream()
                .filter(post -> post.getImageUrl() != null && !post.getImageUrl().isBlank())
                .collect(java.util.stream.Collectors.toList()));
        // Luôn cấp dữ liệu cho tab Đăng lại; trước đây biến này bị bỏ quên khiến
        // /profile/* nổ TemplateProcessingException ngay tại #lists.size(repostPosts).
        model.addAttribute("repostPosts", postService.getRepostsByUser(profileUserId, currentUserId, 0, 20));
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
        // Không chặn việc render cả trang chỉ để đếm badge. Hai con số này được
        // tải song song sau first paint qua /api/notifications/unread-count và
        // /api/chat/unread-count, rồi realtime sẽ cập nhật tiếp.
        model.addAttribute("unreadCount", 0L);
        model.addAttribute("unreadMessageCount", 0L);
    }

    private boolean isLoggedIn(Authentication authentication) {
        return SecurityUtils.currentUserId(authentication) != null;
    }

    private Long requireLogin(Authentication authentication) {
        return SecurityUtils.currentUserId(authentication);
    }
}