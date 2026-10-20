package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.model.document.Comment;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.repository.DailyCountProjection;
import com.aloute.repository.HostUserStatsProjection;
import com.aloute.repository.HostCommunityStatsProjection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

class HostDashboardServiceTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final ICommunityRepository communities = mock(ICommunityRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final HostDashboardService service =
            new HostDashboardService(users, communities, mongo, presence, "Asia/Bangkok");

    private HostUserStatsProjection userStats;
    private HostCommunityStatsProjection communityStats;

    @BeforeEach
    void defaultDatabaseAggregates() {
        userStats = mock(HostUserStatsProjection.class);
        communityStats = mock(HostCommunityStatsProjection.class);
        when(users.loadHostStats(any(), any())).thenReturn(userStats);
        when(communities.loadHostStats(any(), any())).thenReturn(communityStats);
        when(users.countCreatedByDay(any(), any())).thenReturn(List.of());
        when(communities.countCreatedByDay(any(), any())).thenReturn(List.of());
    }

    @Test
    void aggregatesServerTotalsAndZeroFillsDailySeries() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Bangkok"));
        DailyCountProjection userDay = mock(DailyCountProjection.class);
        when(userDay.getDay()).thenReturn(today.minusDays(1));
        when(userDay.getCount()).thenReturn(2L);
        DailyCountProjection communityDay = mock(DailyCountProjection.class);
        when(communityDay.getDay()).thenReturn(today);
        when(communityDay.getCount()).thenReturn(1L);
        when(users.countCreatedByDay(any(), any())).thenReturn(List.of(userDay));
        when(communities.countCreatedByDay(any(), any())).thenReturn(List.of(communityDay));
        when(userStats.getTotalUsers()).thenReturn(120L);
        when(userStats.getLockedAccounts()).thenReturn(4L);
        when(userStats.getNewUsersToday()).thenReturn(3L);
        when(communityStats.getTotalCommunities()).thenReturn(14L);
        when(communityStats.getNewCommunitiesToday()).thenReturn(1L);
        when(mongo.count(any(Query.class), eq(Post.class))).thenReturn(500L);
        when(mongo.count(any(Query.class), eq(Comment.class))).thenReturn(900L);
        when(mongo.count(any(Query.class), eq(ContentReport.class))).thenReturn(6L);
        when(presence.countOnlineUsers()).thenReturn(11L);

        var result = service.load(today.minusDays(6), today);

        assertEquals(120L, result.totalUsers());
        assertEquals(3L, result.newUsersToday());
        assertEquals(500L, result.totalPosts());
        assertEquals(900L, result.totalComments());
        assertEquals(6L, result.pendingReports());
        assertEquals(4L, result.lockedAccounts());
        assertEquals(11L, result.onlineUsers());
        assertEquals(14L, result.totalCommunities());
        assertEquals(1L, result.newCommunitiesToday());
        assertEquals(today.minusDays(6), result.fromDate());
        assertEquals(today, result.toDate());
        assertEquals(7, result.userGrowth().size());
        assertEquals(2L, result.userGrowth().get(5).count());
        assertEquals(1L, result.communityGrowth().get(6).count());
    }

    @Test
    void rejectsRangesLongerThanOneYear() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Bangkok"));
        assertThrows(
                com.aloute.common.exception.BadRequestException.class,
                () -> service.load(today.minusDays(366), today));
    }

    @Test
    void rejectsReversedOrFutureDateRanges() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Bangkok"));

        assertThrows(
                com.aloute.common.exception.BadRequestException.class,
                () -> service.load(today, today.minusDays(1)));
        assertThrows(
                com.aloute.common.exception.BadRequestException.class,
                () -> service.load(today, today.plusDays(1)));
    }

    @Test
    void defaultsToCurrentCalendarMonth() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Bangkok"));
        var result = service.load(null, null);

        assertEquals(today.withDayOfMonth(1), result.fromDate());
        assertEquals(today, result.toDate());
        assertEquals(today.getDayOfMonth(), result.userGrowth().size());
    }
}
