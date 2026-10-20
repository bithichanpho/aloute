package com.aloute.repository;

import com.aloute.model.entity.Notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface INotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByOutboxEventId(String outboxEventId);

    List<Notification> findByReceiverIdOrderByCreatedAtDesc(
            Long receiverId, org.springframework.data.domain.Pageable pageable);

    List<Notification> findByReceiverIdAndIdLessThanOrderByIdDesc(
            Long receiverId, Long beforeId, org.springframework.data.domain.Pageable pageable);

    long countByReceiverIdAndReadFalse(Long receiverId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "update Notification n set n.read = true where n.receiverId = :userId and n.read ="
                    + " false")
    int markAllRead(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceId(String referenceId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceIdIn(Collection<String> referenceIds);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceIdAndTypeIn(String referenceId, Collection<String> types);

    void deleteByReceiverIdOrSenderId(Long receiverId, Long senderId);
}
