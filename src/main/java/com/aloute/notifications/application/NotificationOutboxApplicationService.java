package com.aloute.notifications.application;

import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationOutboxRepository;

/** Writes the delivery intent in PostgreSQL; an active JPA transaction is joined automatically. */
public class NotificationOutboxApplicationService implements com.aloute.notifications.api.NotificationIntents {
    private final INotificationOutboxRepository repository;

    public NotificationOutboxApplicationService(INotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void enqueue(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        persistIntent(receiverId, senderId, type, referenceId, content);
    }

    /**
     * An afterCommit callback runs after the original transaction has physically committed.
     * REQUIRED would join its still-bound resources without a second commit, so use REQUIRES_NEW.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void enqueueAfterCommit(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        persistIntent(receiverId, senderId, type, referenceId, content);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markInAppDelivered(String eventId, java.time.LocalDateTime deliveredAt) {
        if (eventId == null || eventId.isBlank() || deliveredAt == null) return;
        repository.markInAppDeliveredAt(eventId, deliveredAt);
    }

    private void persistIntent(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setReceiverId(receiverId);
        event.setSenderId(senderId);
        event.setNotificationType(type);
        event.setReferenceId(referenceId);
        event.setContent(content);
        repository.save(event);
    }
}
