package com.aloute.repository;

import com.aloute.model.entity.Community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ICommunityRepository extends JpaRepository<Community, Long> {

    List<Community> findByPrivacyOrderByMemberCountDesc(String privacy, Pageable pageable);

    @Query("""
            select c from Community c
            where c.privacy = :privacy
              and not exists (
                select m.id from CommunityMember m
                where m.communityId = c.id and m.userId = :userId and m.role in :joinedRoles
              )
            order by c.memberCount desc, c.id desc
            """)
    List<Community> findDiscoverableForUser(
            @Param("privacy") String privacy,
            @Param("userId") Long userId,
            @Param("joinedRoles") java.util.Collection<String> joinedRoles,
            Pageable pageable);

    List<Community> findByIdIn(List<Long> ids);

    boolean existsByAvatarUrl(String avatarUrl);

    boolean existsByCoverUrl(String coverUrl);

    List<Community> findByNameContainingIgnoreCaseAndPrivacy(
            String keyword, String privacy, Pageable pageable);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            LocalDateTime from, LocalDateTime to);

    @Query(value = """
            select cast(created_at as date) as day, count(*) as count
            from communities
            where created_at >= :from and created_at < :to
            group by cast(created_at as date)
            order by day
            """, nativeQuery = true)
    List<DailyCountProjection> countCreatedByDay(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = """
            select count(*) as \"totalCommunities\",
                   count(*) filter (where created_at >= :todayStart and created_at < :tomorrow)
                       as \"newCommunitiesToday\"
            from communities
            """, nativeQuery = true)
    HostCommunityStatsProjection loadHostStats(
            @Param("todayStart") LocalDateTime todayStart,
            @Param("tomorrow") LocalDateTime tomorrow);
}
