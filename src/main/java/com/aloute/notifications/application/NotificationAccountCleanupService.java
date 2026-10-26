package com.aloute.notifications.application;

import com.aloute.notifications.api.NotificationAccountCleanup;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IUserPushTokenRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationAccountCleanupService implements NotificationAccountCleanup {
    private final INotificationRepository notifications;
    private final INotificationOutboxRepository outbox;
    private final IUserPushTokenRepository pushTokens;

    public NotificationAccountCleanupService(INotificationRepository notifications,
            INotificationOutboxRepository outbox, IUserPushTokenRepository pushTokens) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.pushTokens = pushTokens;
    }

    @Transactional
    public void removeAccountReferences(Long userId) {
        Objects.requireNonNull(userId, "userId");
        pushTokens.deleteByUserId(userId);
        notifications.deleteByReceiverIdOrSenderId(userId, userId);
        if (outbox != null) outbox.deleteByReceiverIdOrSenderId(userId, userId);
    }
}
