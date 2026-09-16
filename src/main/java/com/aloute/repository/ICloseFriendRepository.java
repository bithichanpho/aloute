package com.aloute.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.CloseFriend;

public interface ICloseFriendRepository extends JpaRepository<CloseFriend, Long> {

    Optional<CloseFriend> findByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    List<CloseFriend> findByRequesterIdAndStatus(Long requesterId, String status);

    List<CloseFriend> findByReceiverIdAndStatus(Long receiverId, String status);

    void deleteByRequesterIdAndReceiverId(Long requesterId, Long receiverId);

    /** Toàn bộ lời mời mình đã gửi (mọi trạng thái) — dựng map trạng thái không cần lặp query. */
    List<CloseFriend> findByRequesterId(Long requesterId);

    /** Toàn bộ lời mời mình nhận được (mọi trạng thái). */
    List<CloseFriend> findByReceiverId(Long receiverId);
}