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

    long countByReceiverIdAndReadFalseAndTypeIn(Long receiverId, Collection<String> types);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "update Notification n set n.read = true where n.receiverId = :userId and n.read ="
                    + " false")
    int markAllRead(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceId(String referenceId);

    /** Keep the legacy long-returning API; execute one bulk statement without loading entities. */
    @org.springframework.transaction.annotation.Transactional
    default long deleteByReferenceIdIn(Collection<String> referenceIds) {
        return bulkDeleteByReferenceIds(referenceIds);
    }

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("delete from Notification n where n.referenceId in :referenceIds")
    int bulkDeleteByReferenceIds(@org.springframework.data.repository.query.Param("referenceIds") Collection<String> referenceIds);

    @org.springframework.transaction.annotation.Transactional
    default long deleteByReferenceIdAndTypeIn(String referenceId, Collection<String> types) {
        return bulkDeleteByReferenceAndTypes(referenceId, types);
    }

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("delete from Notification n where"
            + " (n.referenceId = :referenceId or (:referenceId is null and n.referenceId is null)) and n.type in :types")
    int bulkDeleteByReferenceAndTypes(@org.springframework.data.repository.query.Param("referenceId") String referenceId,
            @org.springframework.data.repository.query.Param("types") Collection<String> types);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "delete from Notification n where n.receiverId = :receiverId or n.senderId = :senderId")
    void deleteByReceiverIdOrSenderId(
            @org.springframework.data.repository.query.Param("receiverId") Long receiverId,
            @org.springframework.data.repository.query.Param("senderId") Long senderId);
}
