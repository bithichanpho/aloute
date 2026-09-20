package com.aloute.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aloute.model.entity.Friend;

public interface IFriendRepository extends JpaRepository<Friend, Long> {

    Optional<Friend> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    List<Friend> findByFollowerId(Long followerId);   // danh sách đang follow

    List<Friend> findByFollowedId(Long followedId);   // danh sách follower

    long countByFollowerId(Long followerId);

    long countByFollowedId(Long followedId);

    /** Mọi cạnh follow xuất phát từ một nhóm người — dùng để tính bạn chung trong 1 query. */
    List<Friend> findByFollowerIdIn(Collection<Long> followerIds);

    /** Đếm follower cho nhiều người cùng lúc, thay cho N lần countByFollowedId. */
    @Query("select f.followedId, count(f) from Friend f where f.followedId in :ids group by f.followedId")
    List<Object[]> countFollowersGrouped(@Param("ids") Collection<Long> ids);

    /** Bạn bè = hai chiều follow; lấy trực tiếp bằng 1 query thay cho 2 lần load toàn bộ danh sách. */
    @Query("select f.followedId from Friend f where f.followerId = :userId and exists (select 1 from Friend back where back.followerId = f.followedId and back.followedId = :userId)")
    List<Long> findMutualFollowedIds(@Param("userId") Long userId);
}
