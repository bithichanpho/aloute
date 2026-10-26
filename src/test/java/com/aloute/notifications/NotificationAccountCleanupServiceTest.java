package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.notifications.application.NotificationAccountCleanupService;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IUserPushTokenRepository;
import org.junit.jupiter.api.Test;

class NotificationAccountCleanupServiceTest {
    @Test
    void authorizedAccountDeletionAlsoRemovesPendingDeliveryIntents() {
        var notifications = mock(INotificationRepository.class);
        var outbox = mock(INotificationOutboxRepository.class);
        var tokens = mock(IUserPushTokenRepository.class);
        var service = new NotificationAccountCleanupService(notifications, outbox, tokens);
        assertThrows(NullPointerException.class, () -> service.removeAccountReferences(null));
        verifyNoInteractions(notifications, outbox, tokens);

        service.removeAccountReferences(9L);
        verify(tokens).deleteByUserId(9L);
        verify(notifications).deleteByReceiverIdOrSenderId(9L, 9L);
        verify(outbox).deleteByReceiverIdOrSenderId(9L, 9L);
    }
}
