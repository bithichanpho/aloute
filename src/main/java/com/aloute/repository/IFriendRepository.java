package com.aloute.repository;

import com.aloute.model.entity.Friend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IFriendRepository extends JpaRepository<Friend, Long> {

    interface ProfileRelationshipStats {
        Long getFollowerCount();

        Long getFollowingCount();

        Long getViewerFollowsCount();

        Long getTargetFollowsViewerCount();

        default long followerCount() {
            return getFollowerCount() == null ? 0L : getFollowerCount();
        }

        default long followingCount() {
            return getFollowingCount() == null ? 0L : getFollowingCount();
        }

        default boolean viewerFollows() {
            return getViewerFollowsCount() != null && getViewerFollowsCount() > 0;
        }

        default boolean targetFollowsViewer() {
            return getTargetFollowsViewerCount() != null && getTargetFollowsViewerCount() > 0;
        }
    }

    Optional<Friend> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    List<Friend> findByFollowerId(Long followerId);

    List<Friend> findByFollowerId(Long followerId, org.springframework.data.domain.Pageable pageable);

    @Query(
            "select f.followedId from Friend f where f.followerId = :followerId"
                    + " and f.followedId > :afterId order by f.followedId")
    List<Long> findFollowedIdsAfter(
            @Param("followerId") Long followerId,
            @Param("afterId") Long afterId,
            org.springframework.data.domain.Pageable pageable);

    List<Friend> findByFollowedId(Long followedId);

    List<Friend> findByFollowedId(Long followedId, org.springframework.data.domain.Pageable pageable);

    List<Friend> findByFollowerIdAndFollowedIdIn(Long followerId, Collection<Long> followedIds);

    List<Friend> findByFollowedIdAndFollowerIdIn(Long followedId, Collection<Long> followerIds);

    long countByFollowerId(Long followerId);

    long countByFollowedId(Long followedId);

    /**
     * Provides all follow counters and viewer relationship flags needed by one profile response in
     * a single index-backed aggregate query.
     */
    @Query(
            "select count(case when f.followedId = :targetUserId then 1 else null end)"
                    + " as followerCount,"
                    + " count(case when f.followerId = :targetUserId then 1 else null end)"
                    + " as followingCount,"
                    + " count(case when f.followerId = :viewerId"
                    + " and f.followedId = :targetUserId then 1 else null end)"
                    + " as viewerFollowsCount,"
                    + " count(case when f.followerId = :targetUserId"
                    + " and f.followedId = :viewerId then 1 else null end)"
                    + " as targetFollowsViewerCount"
                    + " from Friend f where f.followedId = :targetUserId"
                    + " or f.followerId = :targetUserId")
    ProfileRelationshipStats getProfileRelationshipStats(
            @Param("targetUserId") Long targetUserId, @Param("viewerId") Long viewerId);

    List<Friend> findByFollowerIdIn(Collection<Long> followerIds);

    @Query(
            "select f.followedId, count(f) from Friend f where f.followedId in :ids group by"
                    + " f.followedId")
    List<Object[]> countFollowersGrouped(@Param("ids") Collection<Long> ids);

    @Query(
            "select f.followedId from Friend f where f.followerId = :userId and exists (select 1"
                    + " from Friend back where back.followerId = f.followedId and back.followedId ="
                    + " :userId)")
    List<Long> findMutualFollowedIds(@Param("userId") Long userId);

    void deleteByFollowerIdOrFollowedId(Long followerId, Long followedId);
}
