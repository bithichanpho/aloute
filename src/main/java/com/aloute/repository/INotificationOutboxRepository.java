package com.aloute.repository;

import com.aloute.model.entity.NotificationOutboxEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface INotificationOutboxRepository
        extends JpaRepository<NotificationOutboxEvent, String> {
    List<NotificationOutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, LocalDateTime now, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceIdIn(Collection<String> referenceIds);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceIdAndNotificationTypeIn(
            String referenceId, Collection<String> notificationTypes);
}
