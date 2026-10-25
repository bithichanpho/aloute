package com.aloute.service;

import com.aloute.notifications.application.NotificationOutboxApplicationService;
import com.aloute.repository.INotificationOutboxRepository;
import org.springframework.stereotype.Service;

/** Existing bean/type retained; durable writes are implemented by the notification owner. */
@Service
public class NotificationOutboxService extends NotificationOutboxApplicationService {
    public NotificationOutboxService(INotificationOutboxRepository repository) {
        super(repository);
    }
}
