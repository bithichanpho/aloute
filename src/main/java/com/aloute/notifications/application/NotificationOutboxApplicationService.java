package com.aloute.notifications.application;

import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationOutboxRepository;

/** Writes the delivery intent in PostgreSQL; an active JPA transaction is joined automatically. */
public class NotificationOutboxApplicationService implements com.aloute.notifications.api.NotificationIntents {
    private final INotificationOutboxRepository repository;

    public NotificationOutboxApplicationService(INotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @org.springframework.transaction.annotation.Transactional
    public void enqueue(
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
