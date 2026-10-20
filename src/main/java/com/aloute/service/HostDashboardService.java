package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.dto.response.DailyMetric;
import com.aloute.dto.response.HostDashboardResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.repository.DailyCountProjection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HostDashboardService {
    private static final long MAX_RANGE_DAYS = 366;
    private final IUserRepository users;
    private final ICommunityRepository communities;
    private final MongoTemplate mongo;
    private final PresenceService presence;
    private final ZoneId zoneId;

    public HostDashboardService(
            IUserRepository users,
            ICommunityRepository communities,
            MongoTemplate mongo,
            PresenceService presence,
            @Value("${app.time-zone:Asia/Bangkok}") String timeZone) {
        this.users = users;
        this.communities = communities;
        this.mongo = mongo;
        this.presence = presence;
        this.zoneId = ZoneId.of(timeZone);
    }

    @Cacheable(
            cacheNames = "hostDashboard",
            key = "(#requestedFrom == null ? 'default' : #requestedFrom.toString()) + ':' + "
                    + "(#requestedTo == null ? 'default' : #requestedTo.toString())",
            sync = true)
    public HostDashboardResponse load(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = today();
        LocalDate lastDay = requestedTo == null ? today : requestedTo;
        LocalDate firstDay = requestedFrom == null ? lastDay.withDayOfMonth(1) : requestedFrom;
        validateRange(firstDay, lastDay, today);
        LocalDateTime from = firstDay.atStartOfDay();
        LocalDateTime until = lastDay.plusDays(1).atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime todayStart = today.atStartOfDay();

        List<DailyMetric> userGrowth = dailySeries(
                firstDay, lastDay, users.countCreatedByDay(from, until));
        List<DailyMetric> communityGrowth = dailySeries(
                firstDay, lastDay, communities.countCreatedByDay(from, until));
        var userStats = users.loadHostStats(todayStart, tomorrow);
        var communityStats = communities.loadHostStats(todayStart, tomorrow);

        return new HostDashboardResponse(
                userStats.getTotalUsers(),
                userStats.getNewUsersToday(),
                mongo.count(new Query(), Post.class),
                mongo.count(new Query(), Comment.class),
                mongo.count(Query.query(Criteria.where("status").is("OPEN")), ContentReport.class),
                userStats.getLockedAccounts(),
                presence.countOnlineUsers(),
                communityStats.getTotalCommunities(),
                communityStats.getNewCommunitiesToday(),
                firstDay,
                lastDay,
                userGrowth,
                communityGrowth);
    }

    public LocalDate today() {
        return LocalDate.now(zoneId);
    }

    private static void validateRange(LocalDate firstDay, LocalDate lastDay, LocalDate today) {
        if (firstDay.isAfter(lastDay)) {
            throw new BadRequestException("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        if (lastDay.isAfter(today)) {
            throw new BadRequestException("Ngày kết thúc không được lớn hơn hôm nay");
        }
        long numberOfDays = ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        if (numberOfDays > MAX_RANGE_DAYS) {
            throw new BadRequestException("Khoảng thống kê tối đa là 366 ngày");
        }
    }

    private static List<DailyMetric> dailySeries(
            LocalDate firstDay, LocalDate lastDay, List<DailyCountProjection> dailyCounts) {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            counts.put(day, 0L);
        }
        if (dailyCounts != null) {
            for (DailyCountProjection row : dailyCounts) {
                if (row == null || row.getDay() == null) continue;
                counts.computeIfPresent(row.getDay(), (day, ignored) -> row.getCount());
            }
        }
        List<DailyMetric> result = new ArrayList<>(counts.size());
        counts.forEach((date, count) -> result.add(new DailyMetric(date, count)));
        return List.copyOf(result);
    }
}
