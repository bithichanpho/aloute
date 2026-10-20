package com.aloute.service;

import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationOutboxRepository;
import org.springframework.stereotype.Service;

/** Writes the delivery intent in PostgreSQL; an active JPA transaction is joined automatically. */
@Service
public class NotificationOutboxService {
    private final INotificationOutboxRepository repository;

    public NotificationOutboxService(INotificationOutboxRepository repository) {
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
