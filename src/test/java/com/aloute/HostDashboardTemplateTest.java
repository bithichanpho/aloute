package com.aloute;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aloute.dto.response.DailyMetric;
import com.aloute.dto.response.HostDashboardResponse;
import com.aloute.dto.response.UserProfileDto;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

class HostDashboardTemplateTest {
    @Test
    void rendersHostMetricsAndDailyCharts() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servlet = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/admin/dashboard");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange, Locale.forLanguageTag("vi"));
        UserProfileDto currentUser = new UserProfileDto();
        currentUser.setId(1L);
        context.setVariable("currentUser", currentUser);
        context.setVariable("activeNav", "host-dashboard");
        context.setVariable("pageTitle", "Quản trị máy chủ");
        context.setVariable("unreadCount", 0L);
        context.setVariable("unreadMessageCount", 0L);
        context.setVariable("isSystemAdmin", false);
        context.setVariable("isHost", true);
        context.setVariable("today", LocalDate.of(2026, 10, 20));
        context.setVariable("maxDailyUsers", 2L);
        context.setVariable("maxDailyCommunities", 1L);
        List<DailyMetric> series = List.of(new DailyMetric(LocalDate.of(2026, 10, 20), 1L));
        context.setVariable("dashboard", new HostDashboardResponse(
                100, 2, 30, 50, 4, 3, 8, 10, 1,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), series, series));

        String html = engine.process("admin/dashboard", context);

        assertTrue(html.contains("Tổng người dùng"));
        assertTrue(html.contains("Quản trị máy chủ"));
        assertTrue(html.contains("Tổng quan"));
        assertTrue(html.contains("Hoạt động hôm nay"));
        assertTrue(html.contains("Trạng thái vận hành"));
        assertTrue(!html.contains("href=\"/admin/moderation\""));
        assertTrue(html.contains("20/10"));
        assertTrue(html.contains("name=\"from\""));
        assertTrue(html.contains("name=\"to\""));
        assertTrue(!html.contains("7 ngày"));
    }
}
