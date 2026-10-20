package com.aloute.controller;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.User;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ChatService;
import com.aloute.service.CommunityService;
import com.aloute.service.DiscoveryService;
import com.aloute.service.FriendService;
import com.aloute.service.GroupService;
import com.aloute.service.NotificationService;
import com.aloute.service.PostService;
import com.aloute.service.PresenceService;
import com.aloute.service.SocialFeatureService;
import com.aloute.service.UserService;

import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Controller
@Validated
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
    private final SocialFeatureService socialFeatureService;

    public ViewController(
            PostService postService,
            UserService userService,
            FriendService friendService,
            NotificationService notificationService,
            ChatService chatService,
            GroupService groupService,
            PresenceService presenceService,
            DiscoveryService discoveryService,
            CommunityService communityService,
            SocialFeatureService socialFeatureService) {
        this.postService = postService;
        this.userService = userService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.groupService = groupService;
        this.presenceService = presenceService;
        this.discoveryService = discoveryService;
        this.communityService = communityService;
        this.socialFeatureService = socialFeatureService;
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

    @GetMapping("/verify-email")
    public String verifyEmailPage(@RequestParam(required = false) String email, Model model) {
        model.addAttribute("email", email);
        return "auth/verify";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Authentication authentication) {
        return isLoggedIn(authentication) ? "redirect:/feed" : "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(required = false) String email, Model model) {
        model.addAttribute("email", email);
        return "auth/reset-password";
    }

    @GetMapping("/feed")
    public String feed(
            Authentication authentication,
            Model model,
            @RequestParam(defaultValue = "foryou") @Pattern(regexp = "(?i)foryou|following", message = "Tab feed không hợp lệ") String tab,
            @RequestParam(required = false) @Size(max = 100, message = "Mã bài viết không hợp lệ") String postId,
            @RequestParam(required = false) @Size(max = 50, message = "Hashtag không hợp lệ") String hashtag) {
        return renderFeed(authentication, model, tab, postId, hashtag);
    }

    @GetMapping("/hashtag/{tag}")
    public String hashtag(
            Authentication authentication,
            Model model,
            @PathVariable @Pattern(regexp = "[\\p{L}\\p{N}_]{1,50}", message = "Hashtag không hợp lệ") String tag) {
        return renderFeed(authentication, model, "foryou", null, tag);
    }

    private String renderFeed(
            Authentication authentication, Model model, String tab, String postId, String hashtag) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        List<PostResponse> posts;
        String nextCursor = "";
        boolean hasMore = false;
        if (postId != null && !postId.isBlank()) {
            posts = List.of(postService.getPost(postId.trim(), userId));
        } else {
            var feedPage = postService.getCursorFeedForViewer(
                    userId, tab, hashtag, null, 20);
            posts = feedPage.items();
            nextCursor = feedPage.nextCursor();
            hasMore = feedPage.hasMore();
        }

        model.addAttribute("posts", posts);
        model.addAttribute("activeTab", hashtag != null && !hashtag.isBlank() ? "hashtag" : tab);
        model.addAttribute("hashtagName", hashtag == null ? "" : hashtag.replaceFirst("^#", ""));
        model.addAttribute("nextFeedCursor", nextCursor);
        model.addAttribute("feedHasMore", hasMore);

        List<ChatGroup> feedGroups = groupService.getMyGroups(userId, 0, 50);
        List<User> mutualFriends = new java.util.ArrayList<>(
                userService.findUsersByIds(friendService.getMutualFriendIds(userId)));
        java.util.Set<String> onlineUsernames = presenceService.onlineUsernames(
                mutualFriends.stream().map(User::getUsername).toList());
        java.util.Set<Long> onlineFriendIds = mutualFriends.stream()
                .filter(friend -> onlineUsernames.contains(friend.getUsername()))
                .map(User::getId).collect(java.util.stream.Collectors.toSet());
        mutualFriends.sort(java.util.Comparator
                .comparing((User friend) -> !onlineFriendIds.contains(friend.getId()))
                .thenComparing(friend -> friend.getFullName() != null && !friend.getFullName().isBlank()
                        ? friend.getFullName() : friend.getUsername(), String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("feedGroups", feedGroups.stream().limit(8).toList());
        model.addAttribute("friends", mutualFriends);
        model.addAttribute("onlineFriendIds", onlineFriendIds);
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
    public String chat(
            Authentication authentication,
            Model model,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long userId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long groupId) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";

        var conversationPage = chatService.getConversationPage(currentUserId, null, 30);
        List<ConversationResponse> conversations = new java.util.ArrayList<>(conversationPage.items());
        if (userId != null && conversations.stream()
                .noneMatch(item -> !item.isGroup() && userId.equals(item.getOtherUserId()))) {
            chatService.getConversations(currentUserId, userId).stream()
                    .filter(item -> !item.isGroup() && userId.equals(item.getOtherUserId()))
                    .findFirst().ifPresent(item -> conversations.add(0, item));
        }
        model.addAttribute("conversations", conversations);
        model.addAttribute("conversationNextCursor", conversationPage.nextCursor());
        model.addAttribute("conversationHasMore", conversationPage.hasMore());

        model.addAttribute("openUserId", userId);
        model.addAttribute("openGroupId", groupId);
        addCommonAttributes(model, currentUserId, "chat");
        return "chat/index";
    }

    @GetMapping("/people/{username}")
    public String mentionedProfile(
            Authentication authentication,
            @PathVariable @Size(min = 3, max = 50, message = "Tên đăng nhập không hợp lệ") String username) {
        Long viewer = requireLogin(authentication);
        if (viewer == null) return "redirect:/login";
        return userService.searchUsers(username, viewer).stream()
                .filter(u -> u.getUsername().equalsIgnoreCase(username))
                .findFirst()
                .map(u -> "redirect:/profile/" + u.getId())
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "Không tìm thấy tài khoản"));
    }

    @GetMapping("/profile/me")
    public String myProfile(Authentication authentication, Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        return renderProfile(userId, userId, model);
    }

    @GetMapping("/profile/{userId}")
    public String userProfile(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            Model model) {
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
    public String feedFragment(
            Authentication authentication,
            Model model,
            jakarta.servlet.http.HttpServletResponse response,
            @RequestParam(defaultValue = "foryou") @Pattern(regexp = "(?i)foryou|following", message = "Tab feed không hợp lệ") String tab,
            @RequestParam(required = false) @Size(max = 256, message = "Cursor không hợp lệ") String cursor,
            @RequestParam(required = false) @Size(max = 50, message = "Hashtag không hợp lệ") String tag) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        var page = postService.getCursorFeedForViewer(
                userId, tab, tag, cursor, 20);
        model.addAttribute("posts", page.items());
        var currentUser = new UserProfileDto();
        currentUser.setId(userId);
        model.addAttribute("currentUser", currentUser);
        response.setHeader("X-Next-Cursor", page.nextCursor());
        response.setHeader("X-Has-More", Boolean.toString(page.hasMore()));
        return "feed/index :: .post";
    }

    @GetMapping("/api/posts/user/{userId}/fragment")
    public String profileFragment(
            Authentication authentication,
            Model model,
            jakarta.servlet.http.HttpServletResponse response,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam(required = false) @Size(max = 256) String cursor) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";
        var postPage = postService.getPostsByUserCursor(userId, currentUserId, cursor, 20);
        model.addAttribute("userPosts", postPage.items());

        model.addAttribute("repostPosts", Collections.emptyList());
        model.addAttribute(
                "mediaPosts",
                postPage.items().stream()
                        .filter(
                                post ->
                                        (post.getImageUrl() != null
                                                        && !post.getImageUrl().isBlank())
                                                || (post.getVideoUrl() != null
                                                        && !post.getVideoUrl().isBlank()))
                        .collect(java.util.stream.Collectors.toList()));
        model.addAttribute("isOwnProfile", currentUserId.equals(userId));
        response.setHeader("X-Next-Cursor", postPage.nextCursor());
        response.setHeader("X-Has-More", Boolean.toString(postPage.hasMore()));
        return "profile/view :: profileTimelinePost";
    }

    @GetMapping("/api/posts/user/{userId}/reposts/fragment")
    public String profileRepostFragment(
            Authentication authentication,
            Model model,
            jakarta.servlet.http.HttpServletResponse response,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId,
            @RequestParam(required = false) @Size(max = 256) String cursor) {
        Long currentUserId = requireLogin(authentication);
        if (currentUserId == null) return "redirect:/login";

        var repostPage = postService.getRepostsByUserCursor(userId, currentUserId, cursor, 20);

        model.addAttribute("repostPosts", repostPage.items());
        model.addAttribute("userPosts", Collections.emptyList());
        model.addAttribute("mediaPosts", Collections.emptyList());
        model.addAttribute("isOwnProfile", currentUserId.equals(userId));
        response.setHeader("X-Next-Cursor", repostPage.nextCursor());
        response.setHeader("X-Has-More", Boolean.toString(repostPage.hasMore()));
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
        model.addAttribute("pageKind", "saved");
        model.addAttribute("savedPosts", socialFeatureService.saved(userId, 0));
        return "features/index";
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
    public String communityDetail(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            Model model) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";

        model.addAttribute("community", communityService.getCommunity(communityId, userId));

        model.addAttribute("feedLocked", false);
        try {
            model.addAttribute("posts", communityService.getFeed(communityId, userId, 0, 20));
        } catch (ForbiddenException e) {

            model.addAttribute("posts", java.util.Collections.emptyList());
            model.addAttribute("feedLocked", true);
        }
        addCommonAttributes(model, userId, "communities");
        return "community/view";
    }

    @GetMapping("/api/communities/{communityId}/posts/fragment")
    public String communityFeedFragment(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã hội nhóm không hợp lệ") Long communityId,
            Model model,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") @Max(value = 100, message = "Trang quá sâu") int page) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        model.addAttribute("posts", communityService.getFeed(communityId, userId, page, 20));
        var currentUser = new UserProfileDto();
        currentUser.setId(userId);
        model.addAttribute("currentUser", currentUser);
        return "feed/index :: .post";
    }

    private String renderProfile(Long profileUserId, Long currentUserId, Model model) {
        UserProfileDto profile = userService.getProfile(profileUserId, currentUserId);
        var postPage = postService.getPostsByUserCursor(profileUserId, currentUserId, null, 20);
        List<PostResponse> userPosts = postPage.items();

        model.addAttribute("profileUser", profile);
        model.addAttribute("userPosts", userPosts);

        model.addAttribute(
                "mediaPosts",
                userPosts.stream()
                        .filter(
                                post ->
                                        (post.getImageUrl() != null
                                                        && !post.getImageUrl().isBlank())
                                                || (post.getVideoUrl() != null
                                                        && !post.getVideoUrl().isBlank()))
                        .collect(java.util.stream.Collectors.toList()));

        model.addAttribute("profilePostsNextCursor", postPage.nextCursor());
        model.addAttribute("profilePostsHasMore", postPage.hasMore());
        model.addAttribute("repostPosts", Collections.emptyList());
        model.addAttribute("profileRepostsDeferred", true);
        model.addAttribute("profileRepostsNextCursor", "");
        model.addAttribute("profileRepostsHasMore", true);
        model.addAttribute("postCount", postService.countPostsByUser(profileUserId));
        model.addAttribute(
                "isOwnProfile", currentUserId != null && currentUserId.equals(profileUserId));
        if (currentUserId != null) {
            addCommonAttributes(model, currentUserId, "profile");
        }
        return "profile/view";
    }

    @GetMapping({"/settings", "/admin/moderation"})
    public String featurePage(
            Authentication authentication,
            Model model,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int queuePage) {
        Long userId = requireLogin(authentication);
        if (userId == null) return "redirect:/login";
        String path = request.getRequestURI();
        if (path.startsWith("/admin")
                && authentication.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new UnauthorizedException(
                    "Chỉ quản trị viên xử lý báo cáo được truy cập");
        boolean settingsPage = "/settings".equals(path);
        String pageKind = settingsPage ? "settings" : "moderation";
        model.addAttribute("pageTitle", settingsPage ? "Cài đặt và an toàn" : "Xử lý báo cáo vi phạm");
        model.addAttribute("pageKind", pageKind);
        if (settingsPage) {
            var blockPage = socialFeatureService.blocks(userId, null, 50);
            var requestPage = socialFeatureService.requests(userId, null, 50);
            model.addAttribute("blocks", blockPage.items());
            model.addAttribute("blocksNextCursor", blockPage.nextCursor());
            model.addAttribute("blocksHasMore", blockPage.hasMore());
            model.addAttribute("followRequests", requestPage.items());
            model.addAttribute("requestsNextCursor", requestPage.nextCursor());
            model.addAttribute("requestsHasMore", requestPage.hasMore());
            model.addAttribute("profileUser", userService.getProfile(userId, userId));
        } else {
            model.addAttribute("moderationQueue", socialFeatureService.queue(userId, queuePage, 20));
        }
        addCommonAttributes(model, userId, "features");
        return "features/index";
    }

    private void addCommonAttributes(Model model, Long currentUserId, String activeNav) {
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        model.addAttribute(
                "isSystemAdmin",
                authentication != null
                        && authentication.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        model.addAttribute(
                "isHost",
                authentication != null
                        && authentication.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_HOST")));
        model.addAttribute("activeNav", activeNav);
        if (currentUserId == null) return;
        model.addAttribute("currentUser", userService.getLiteProfile(currentUserId));

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
