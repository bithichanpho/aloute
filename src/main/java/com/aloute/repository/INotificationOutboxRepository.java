package com.aloute.repository;

import com.aloute.model.entity.NotificationOutboxEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface INotificationOutboxRepository
        extends JpaRepository<NotificationOutboxEvent, String> {
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "delete from NotificationOutboxEvent e where e.receiverId = :receiverId or e.senderId = :senderId")
    void deleteByReceiverIdOrSenderId(
            @org.springframework.data.repository.query.Param("receiverId") Long receiverId,
            @org.springframework.data.repository.query.Param("senderId") Long senderId);

    List<NotificationOutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, LocalDateTime now, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update NotificationOutboxEvent e set e.inAppDeliveredAt = :deliveredAt "
                    + "where e.id = :eventId and e.inAppDeliveredAt is null")
    int markInAppDeliveredAt(
            @org.springframework.data.repository.query.Param("eventId") String eventId,
            @org.springframework.data.repository.query.Param("deliveredAt") LocalDateTime deliveredAt);

    @org.springframework.transaction.annotation.Transactional
    default long deleteByReferenceIdIn(Collection<String> referenceIds) {
        return bulkDeleteByReferenceIds(referenceIds);
    }

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("delete from NotificationOutboxEvent e where e.referenceId in :referenceIds")
    int bulkDeleteByReferenceIds(@org.springframework.data.repository.query.Param("referenceIds") Collection<String> referenceIds);

    @org.springframework.transaction.annotation.Transactional
    default long deleteByReferenceIdAndNotificationTypeIn(String referenceId, Collection<String> notificationTypes) {
        return bulkDeleteByReferenceAndTypes(referenceId, notificationTypes);
    }

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("delete from NotificationOutboxEvent e where"
            + " (e.referenceId = :referenceId or (:referenceId is null and e.referenceId is null))"
            + " and e.notificationType in :types")
    int bulkDeleteByReferenceAndTypes(@org.springframework.data.repository.query.Param("referenceId") String referenceId,
            @org.springframework.data.repository.query.Param("types") Collection<String> notificationTypes);
}
