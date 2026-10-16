package com.aloute.repository;

import com.aloute.model.entity.CloseFriend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ICloseFriendRepository extends JpaRepository<CloseFriend, Long> {

    Optional<CloseFriend> findByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    List<CloseFriend> findByRequesterIdAndStatus(Long requesterId, String status);

    List<CloseFriend> findByReceiverIdAndStatus(Long receiverId, String status);

    void deleteByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    List<CloseFriend> findByRequesterId(Long requesterId);

    List<CloseFriend> findByReceiverId(Long receiverId);

    void deleteByRequesterIdOrReceiverId(Long requesterId, Long receiverId);
}
