package com.aloute.repository;

import com.aloute.model.entity.Friend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IFriendRepository extends JpaRepository<Friend, Long> {

    Optional<Friend> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    List<Friend> findByFollowerId(Long followerId);

    List<Friend> findByFollowedId(Long followedId);

    List<Friend> findByFollowerIdAndFollowedIdIn(Long followerId, Collection<Long> followedIds);

    List<Friend> findByFollowedIdAndFollowerIdIn(Long followedId, Collection<Long> followerIds);

    long countByFollowerId(Long followerId);

    long countByFollowedId(Long followedId);

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
