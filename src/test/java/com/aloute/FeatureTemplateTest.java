package com.aloute;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.dto.response.UserProfileDto;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;

class FeatureTemplateTest {
    @Test
    void searchKeepsTabsAvailableBeforeTyping() throws Exception {
        String html = render("search/index", false, "search", java.util.Map.of("activeNav", "search"));
        assertTrue(html.contains("id=\"searchTabs\""));
        assertFalse(html.contains("id=\"searchTabs\" hidden"));
        assertTrue(html.contains("id=\"searchResults\" hidden"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/search-template.html"), html);
    }

    private String render(String template, boolean admin) {
        return render(template, admin, "settings");
    }

    private String render(String template, boolean admin, String pageKind) {
        return render(template, admin, pageKind, java.util.Map.of());
    }

    private String render(String template, boolean admin, String pageKind, java.util.Map<String, Object> extras) {
        return render(template, admin, pageKind, extras, null);
    }

    private String render(String template, boolean admin, String pageKind,
            java.util.Map<String, Object> extras, java.util.Set<String> selectors) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        MockServletContext servlet = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/settings");
        var exchange =
                JakartaServletWebApplication.buildApplication(servlet)
                        .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange, Locale.forLanguageTag("vi"));
        UserProfileDto user = new UserProfileDto();
        user.setId(1L);
        user.setUsername("tester");
        context.setVariable("currentUser", user);
        context.setVariable("profileUser", user);
        context.setVariable("activeNav", "features");
        context.setVariable("unreadCount", 0L);
        context.setVariable("unreadMessageCount", 0L);
        context.setVariable("pageTitle", "Cài đặt và an toàn");
        context.setVariable("pageKind", pageKind);
        context.setVariable("isSystemAdmin", admin);
        context.setVariable("blocks", java.util.List.of());
        context.setVariable("followRequests", java.util.List.of());
        context.setVariable("savedPosts", java.util.List.of());
        context.setVariable(
                "moderationQueue",
                java.util.Map.of(
                        "reports", java.util.List.of(),
                        "flaggedPosts", java.util.List.of(),
                        "posts", java.util.List.of(),
                        "comments", java.util.List.of()));
        extras.forEach(context::setVariable);
        return selectors == null ? engine.process(template, context) : engine.process(template, selectors, context);
    }

    private java.util.List<com.aloute.dto.response.PostResponse> savedPostFixtures() {
        var items = new java.util.ArrayList<com.aloute.dto.response.PostResponse>();
        for (var id : java.util.List.of("plain-saved", "image-saved", "video-saved", "quote-saved", "flagged-saved")) {
            var post = new com.aloute.dto.response.PostResponse();
            post.setId(id); post.setUserId(6L); post.setUsername("minhan");
            post.setUserAvatarUrl("/images/avatar_default.png");
            post.setTimeAgo("1 giờ trước");
            post.setVisibility(com.aloute.model.document.PostVisibility.PUBLIC);
            post.setModerationStatus(com.aloute.model.document.ModerationStatus.APPROVED);
            post.setContent("Nội dung " + id);
            items.add(post);
        }
        items.get(0).setContent("Xin chào <script>bad()</script>");
        items.get(1).setImageUrl("/fixture/saved-image.svg");
        items.get(2).setVideoUrl("/fixture/saved-video.mp4");
        items.get(4).setModerationStatus(com.aloute.model.document.ModerationStatus.FLAGGED);
        var origin = new com.aloute.dto.response.PostResponse.RepostOrigin();
        origin.setPostId("original-post"); origin.setUserId(7L); origin.setUsername("tacgiagoc");
        origin.setContent("Bài gốc được trích dẫn"); origin.setTimeAgo("2 giờ trước");
        origin.setVisibility(com.aloute.model.document.PostVisibility.PUBLIC);
        origin.setImageUrl("/fixture/saved-image.svg");
        items.get(3).setRepostOf(origin);
        return items;
    }

    @Test
    void savedPageUsesFeedCardsAndKeepsSavedIdSeparateFromRepostInteractionId() throws Exception {
        String html = render("features/index", false, "saved", java.util.Map.of(
                "savedPosts", savedPostFixtures(), "pageTitle", "Đã lưu"));
        for (String marker : java.util.List.of("saved-stream", "post__avatar", "post__username", "post__image",
                "post__video", "action--like", "action--reply", "action--repost", "action--share", "post__menu-trigger",
                "data-saved-post=\"quote-saved\"", "data-action-post-id=\"original-post\"", "Bị đánh dấu")) {
            assertTrue(html.contains(marker), marker);
        }
        assertTrue(html.contains("Xin chào &lt;script&gt;bad()&lt;/script&gt;"));
        assertFalse(html.contains("Xin chào <script>bad()</script>"));
        assertFalse(html.contains("feature-saved__body"));
        assertFalse(html.contains("data-action=\"unsave\""));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/saved-feed-template.html"), html);
    }

    @Test
    void feedFragmentSelectorStillRendersItsOwnLoopWithoutSavedPageMarkers() {
        var posts = savedPostFixtures();
        String html = render("feed/index", false, "feed", java.util.Map.of("posts", posts), java.util.Set.of(".post"));
        for (var post : posts) assertTrue(html.contains("data-post-id=\"" + post.getId() + "\""));
        assertFalse(html.contains("data-saved-post="));
        assertFalse(html.contains("<html"));
        assertTrue(html.contains("loading=\"lazy\""));
        assertTrue(html.contains("data-action-post-id=\"original-post\""));
    }

    @Test
    void featurePageRendersWithoutExposingAdminLinkToOrdinaryUsers() {
        String html = render("features/index", false);
        assertTrue(html.contains("id=\"featureContent\""));
        assertTrue(html.contains("/js/features.js"));
        assertFalse(html.contains("href=\"/admin/moderation\""));
    }

    @Test
    void featureNavigationIsNotDuplicatedInsideContent() {
        assertFalse(render("features/index", true).contains("class=\"feature-nav\""));
    }

    @Test
    void featureVariantsRenderServerSideContent() {
        assertTrue(render("features/index", false, "saved").contains("Chưa có bài viết đã lưu"));
        String reportPage = render("features/index", true, "moderation");
        assertTrue(reportPage.contains("Không có báo cáo hoặc bài viết bị đánh dấu"));
        assertFalse(reportPage.contains("Bài viết cần duyệt"));
        assertFalse(reportPage.contains("Bình luận cần duyệt"));
    }

    @Test
    void composeTemplateUsesSharedPostComposer() throws Exception {
        String html = render("feed/compose", false);
        assertTrue(html.contains("id=\"postVisibility\""));
        assertTrue(html.contains("data-audience-value=\"CLOSE_FRIENDS\""));
        assertTrue(html.contains("id=\"postAudienceMenu\""));
        assertTrue(html.contains("post-audience-option__icon"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/privacy-compose-template.html"), html);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/compose-audience-template.html"), html);
    }

    @Test
    void requestRowsRenderPublicAvatarNameProfileLinkAndBothActions() {
        var request=new com.aloute.dto.response.FollowRequestResponse("6:1",6L,1L,java.time.Instant.now(),"minhan","Minh An","/minhan.png");
        String html=render("features/index",false,"settings",java.util.Map.of("followRequests",java.util.List.of(request)));
        for(String marker:java.util.List.of("Minh An","@minhan","src=\"/minhan.png\"","href=\"/profile/6\"","data-follow-request-actions=\"6:1\"")) assertTrue(html.contains(marker),marker);
        assertFalse(html.contains("Tài khoản #6"));
    }

    @Test
    void dedicatedFollowRequestPageKeepsPublicLabelsAndEscapesNames() throws Exception {
        var request = new com.aloute.dto.response.FollowRequestResponse("6:1", 6L, 1L,
                java.time.Instant.now(), "minhan", "Minh <script>bad()</script>", "/minhan.png");
        String html = render("follow-requests/index", false, "follow-requests",
                java.util.Map.of("followRequests", java.util.List.of(request), "requestsHasMore", false));
        assertTrue(html.contains("href=\"/profile/6\""));
        assertTrue(html.contains("Minh &lt;script&gt;bad()&lt;/script&gt;"));
        assertTrue(html.contains("data-follow-request-actions=\"6:1\""));
        assertTrue(html.contains("id=\"followRequestBadge\""));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/follow-requests-template.html"), html);
    }

    @Test
    void blockedRowsRenderLikeFollowRequestsAndEscapePublicNames() throws Exception {
        var known = new com.aloute.dto.response.BlockedAccountResponse("1:6",1L,6L,java.time.Instant.now(),"alice","Alice <script>bad()</script>","/alice.png");
        var missing = new com.aloute.dto.response.BlockedAccountResponse("1:7",1L,7L,java.time.Instant.now(),null,null,null);
        String html = render("features/index",false,"settings",java.util.Map.of("blocks",java.util.List.of(known,missing)));
        for (String marker : java.util.List.of("@alice","src=\"/alice.png\"","href=\"/profile/6\"","data-block-profile","data-action=\"unblock\"","Tài khoản không còn khả dụng","/images/avatar_default.png")) assertTrue(html.contains(marker),marker);
        assertTrue(html.contains("Alice &lt;script&gt;bad()&lt;/script&gt;"));
        assertFalse(html.contains("Alice <script>bad()</script>")); assertFalse(html.contains("Tài khoản #6"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/blocked-accounts-template.html"),html);
    }

    @Test
    void activityKeepsRequestsOnlyInDedicatedNavigation() {
        var request = new com.aloute.dto.response.FollowRequestResponse("6:1", 6L, 1L,
                java.time.Instant.now(), "alice", "Alice", "/alice.png");
        String html = render("activity/index", false, "activity", java.util.Map.of(
                "notifications", java.util.List.of(), "activityFollowRequests", java.util.List.of(request),
                "activityRequestsHasMore", true));
        assertFalse(html.contains("data-follow-request-actions="));
        assertFalse(html.contains("src=\"/alice.png\""));
        assertTrue(html.contains("href=\"/follow-requests\""));
        assertTrue(html.contains("Chưa có thông báo nào."));
    }

    @Test
    void profileOnlyShowsDecisionButtonsForIncomingRequest() {
        var profile = new UserProfileDto(); profile.setId(6L); profile.setUsername("alice");
        profile.setIncomingFollowRequestId("6:1");
        var extras = java.util.Map.<String, Object>of("profileUser", profile, "isOwnProfile", false,
                "userPosts", java.util.List.of(), "privateContentRestricted", true, "postCount", 0L);
        String html = render("profile/view", false, "profile", extras);
        assertTrue(html.contains("data-follow-request-actions=\"6:1\""));
        assertTrue(html.contains("data-follow-profile=\"true\""));
        profile.setIncomingFollowRequestId(null);
        assertFalse(render("profile/view", false, "profile", extras).contains("data-follow-request-actions="));
    }

    @Test
    void closeFriendInvitationsRenderBothDecisionsOnlyForMutualFollowers() {
        var profile = new UserProfileDto(); profile.setId(6L); profile.setUsername("alice");
        profile.setMutualFriend(true); profile.setCloseFriendStatus("PENDING_RECEIVED");
        var extras = java.util.Map.<String, Object>of("profileUser", profile, "isOwnProfile", false,
                "userPosts", java.util.List.of(), "privateContentRestricted", false, "postCount", 0L);
        String html = render("profile/view", false, "profile", extras);
        assertTrue(html.contains("class=\"close-friend-request-bar__accept\""));
        assertTrue(html.contains("class=\"close-friend-request-bar__decline\""));
        profile.setMutualFriend(false);
        assertFalse(render("profile/view", false, "profile", extras).contains("class=\"close-friend-request-bar__accept\""));
    }
}
