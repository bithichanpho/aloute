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
    void composeTemplateUsesSharedPostComposer() {
        String html = render("feed/compose", false);
        assertTrue(html.contains("id=\"postVisibility\""));
    }
}
