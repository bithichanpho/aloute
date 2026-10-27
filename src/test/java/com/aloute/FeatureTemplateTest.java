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
    private String render(String template, boolean admin) {
        return render(template, admin, "settings");
    }

    private String render(String template, boolean admin, String pageKind) {
        return render(template,admin,pageKind,java.util.Map.of());
    }

    private String render(String template, boolean admin, String pageKind, java.util.Map<String,Object> extras) {
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
                        "posts", java.util.List.of(),
                        "comments", java.util.List.of()));
        extras.forEach(context::setVariable);
        return engine.process(template, context);
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
        assertTrue(reportPage.contains("Không có báo cáo đang chờ"));
        assertFalse(reportPage.contains("Bài viết cần duyệt"));
        assertFalse(reportPage.contains("Bình luận cần duyệt"));
    }

    @Test
    void requestRowsRenderPublicAvatarNameProfileLinkAndBothActions() {
        var request=new com.aloute.dto.response.FollowRequestResponse("6:1",6L,1L,java.time.Instant.now(),"minhan","Minh An","/minhan.png");
        String html=render("features/index",false,"settings",java.util.Map.of("followRequests",java.util.List.of(request)));
        for(String marker:java.util.List.of("Minh An","@minhan","src=\"/minhan.png\"","href=\"/@minhan\"","data-follow-request-actions=\"6:1\"")) assertTrue(html.contains(marker),marker);
        assertFalse(html.contains("Tài khoản #6"));
    }

    @Test
    void dedicatedFollowRequestPageKeepsPublicLabelsAndEscapesNames() throws Exception {
        var request = new com.aloute.dto.response.FollowRequestResponse("6:1", 6L, 1L,
                java.time.Instant.now(), "minhan", "Minh <script>bad()</script>", "/minhan.png");
        String html = render("follow-requests/index", false, "follow-requests",
                java.util.Map.of("followRequests", java.util.List.of(request), "requestsHasMore", false));
        assertTrue(html.contains("href=\"/@minhan\""));
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
        for (String marker : java.util.List.of("@alice","src=\"/alice.png\"","href=\"/@alice\"","data-block-profile","data-action=\"unblock\"","Tài khoản không còn khả dụng","/images/avatar_default.png")) assertTrue(html.contains(marker),marker);
        assertTrue(html.contains("Alice &lt;script&gt;bad()&lt;/script&gt;"));
        assertFalse(html.contains("Alice <script>bad()</script>")); assertFalse(html.contains("Tài khoản #6"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/blocked-accounts-template.html"),html);
    }

    @Test
    void legacyEmailUsernameIsEncodedOnceAndNavigationUsesOwnHandle() {
        var request = new com.aloute.dto.response.FollowRequestResponse("6:1",6L,1L,java.time.Instant.now(),"alice@example.com","Alice",null);
        String html = render("features/index",false,"settings",java.util.Map.of("followRequests",java.util.List.of(request)));
        assertTrue(html.contains("href=\"/@alice%40example.com\""));
        assertTrue(html.contains("href=\"/@tester\""));
        assertFalse(html.contains("%2540"));
    }

    @Test
    void activityRendersNotificationActionsAndLegacyPendingRequests() {
        var request=new com.aloute.dto.response.FollowRequestResponse("6:1",6L,1L,java.time.Instant.now(),"minhan","Minh An","/minhan.png");
        var n=new com.aloute.dto.response.NotificationResponse(); n.setId(10L); n.setSenderId(7L); n.setType("FOLLOW_REQUEST");
        n.setSenderAvatarUrl("/alice.png"); n.setFollowRequestId("7:1"); n.setContent("Alice đã gửi yêu cầu theo dõi bạn"); n.setCreatedAt(java.time.LocalDateTime.now());
        String html=render("activity/index",false,"activity",java.util.Map.of("notifications",java.util.List.of(n),"activityFollowRequests",java.util.List.of(request),"activityRequestsHasMore",true));
        for(String marker:java.util.List.of("src=\"/alice.png\"","src=\"/minhan.png\"","data-follow-request-actions=\"6:1\"","data-follow-request-actions=\"7:1\"")) assertTrue(html.contains(marker),marker);
        assertFalse(html.contains("Chưa có thông báo nào."));
    }

    @Test
    void composeTemplateUsesSharedPostComposer() throws Exception {
        String html = render("feed/compose", false);
        assertTrue(html.contains("id=\"postVisibility\""));
        assertTrue(html.contains("id=\"postAudienceDialog\""));
        for (String audience : java.util.List.of("PUBLIC","FRIENDS","CLOSE_FRIENDS","ONLY_ME"))
            assertTrue(html.contains("value=\""+audience+"\""));
        assertFalse(html.contains("Không hiển thị với"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/compose-audience-template.html"),html);
    }
}
