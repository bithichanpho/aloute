package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.HostDashboardResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.HostDashboardService;
import com.aloute.service.UserService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;

@Controller
@Validated
public class HostDashboardController {
    private final HostDashboardService dashboardService;
    private final UserService userService;

    public HostDashboardController(
            HostDashboardService dashboardService, UserService userService) {
        this.dashboardService = dashboardService;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('HOST')")
    @GetMapping("/admin/dashboard")
    public String dashboard(
            Authentication authentication,
            Model model,
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long userId = SecurityUtils.currentUserId(authentication);
        HostDashboardResponse dashboard = dashboardService.load(from, to);
        model.addAttribute("pageTitle", "Quản trị máy chủ");
        model.addAttribute("activeNav", "host-dashboard");
        model.addAttribute("currentUser", userService.getLiteProfile(userId));
        model.addAttribute("unreadCount", 0L);
        model.addAttribute("unreadMessageCount", 0L);
        model.addAttribute("isSystemAdmin", false);
        model.addAttribute("isHost", true);
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("today", dashboardService.today());
        model.addAttribute("maxDailyUsers", maxCount(dashboard.userGrowth()));
        model.addAttribute("maxDailyCommunities", maxCount(dashboard.communityGrowth()));
        return "admin/dashboard";
    }

    @PreAuthorize("hasRole('HOST')")
    @ResponseBody
    @GetMapping("/api/host/dashboard")
    public ApiResponse<HostDashboardResponse> dashboardData(
            @RequestParam(name = "from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(dashboardService.load(from, to));
    }

    private static long maxCount(java.util.List<com.aloute.dto.response.DailyMetric> metrics) {
        return Math.max(1L, metrics.stream().mapToLong(com.aloute.dto.response.DailyMetric::count).max().orElse(0L));
    }
}
