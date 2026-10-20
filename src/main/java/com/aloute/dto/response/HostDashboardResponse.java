package com.aloute.dto.response;

import java.time.LocalDate;
import java.util.List;

public record HostDashboardResponse(
        long totalUsers,
        long newUsersToday,
        long totalPosts,
        long totalComments,
        long pendingReports,
        long lockedAccounts,
        long onlineUsers,
        long totalCommunities,
        long newCommunitiesToday,
        LocalDate fromDate,
        LocalDate toDate,
        List<DailyMetric> userGrowth,
        List<DailyMetric> communityGrowth) {}
