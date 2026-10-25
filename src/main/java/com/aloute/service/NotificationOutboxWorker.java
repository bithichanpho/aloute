package com.aloute.service;

import com.aloute.notifications.application.NotificationDeliveryWorker;
import com.aloute.repository.INotificationOutboxRepository;
import org.springframework.stereotype.Service;

/** One scheduled bean only. Spring discovers the inherited schedule and ShedLock annotations. */
@Service
public class NotificationOutboxWorker extends NotificationDeliveryWorker {
    public NotificationOutboxWorker(INotificationOutboxRepository repository, NotificationService notifications) {
        super(repository, notifications);
    }
}
