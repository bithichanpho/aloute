package com.aloute.repository;

import com.aloute.model.entity.CloseFriend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ICloseFriendRepository extends JpaRepository<CloseFriend, Long> {

    Optional<CloseFriend> findByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    List<CloseFriend> findByRequesterIdAndStatus(Long requesterId, String status);

    List<CloseFriend> findByReceiverIdAndStatus(Long receiverId, String status);

    @org.springframework.data.jpa.repository.Query(
            "select count(c) from CloseFriend c where c.status = :status and "
                    + "(c.requesterId = :userId or c.receiverId = :userId)")
    long countRelationships(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("status") String status);

    @org.springframework.data.jpa.repository.Query(
            "select c from CloseFriend c where (c.requesterId = :userId and c.receiverId in :otherIds)"
                    + " or (c.receiverId = :userId and c.requesterId in :otherIds)")
    List<CloseFriend> findRelationships(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("otherIds") java.util.Collection<Long> otherIds);

    @org.springframework.data.jpa.repository.Query(
            "select case when c.requesterId = :userId then c.receiverId else c.requesterId end"
                    + " from CloseFriend c where c.status = :status and"
                    + " (c.requesterId = :userId or c.receiverId = :userId) order by c.id desc")
    List<Long> findRelatedUserIds(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("status") String status,
            org.springframework.data.domain.Pageable pageable);

    void deleteByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    List<CloseFriend> findByRequesterId(Long requesterId);

    List<CloseFriend> findByReceiverId(Long receiverId);

    void deleteByRequesterIdOrReceiverId(Long requesterId, Long receiverId);
}
