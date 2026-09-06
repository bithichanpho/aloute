package com.aloute.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.Friend;

public interface IFriendRepository extends JpaRepository<Friend, Long> {

    Optional<Friend> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    List<Friend> findByFollowerId(Long followerId);   // danh sách đang follow

    List<Friend> findByFollowedId(Long followedId);   // danh sách follower

    long countByFollowerId(Long followerId);

    long countByFollowedId(Long followedId);
}
