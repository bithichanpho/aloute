package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.notifications.application.NotificationCleanupService;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationCleanupServiceTest {
    private final INotificationRepository notifications = mock(INotificationRepository.class);
    private final INotificationOutboxRepository outbox = mock(INotificationOutboxRepository.class);
    private final NotificationCleanupService service = new NotificationCleanupService(notifications, outbox);

    @Test
    void cleanupRemovesPendingAndVisibleReferencesViaTheOwner() {
        service.removePostReferences(List.of("p1", "p1", "p2"));
        verify(notifications).deleteByReferenceIdIn(List.of("p1", "p2"));
        verify(outbox).deleteByReferenceIdIn(List.of("p1", "p2"));
    }

    @Test
    void communityCleanupPreservesOtherTypesWithTheSameReferenceId() {
        service.removeCommunityReferences(42L);
        List<String> types = List.of("COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE");
        verify(notifications).deleteByReferenceIdAndTypeIn("42", types);
        verify(outbox).deleteByReferenceIdAndNotificationTypeIn("42", types);
        verify(notifications, never()).deleteByReferenceId(any());
    }

    @Test
    void failureIsPropagatedAndNoUnboundedDeleteIsAttempted() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(notifications).deleteByReferenceIdIn(any());
        assertThrows(IllegalStateException.class, () -> service.removePostReferences(List.of("p")));
        verifyNoInteractions(outbox);
    }

    @Test
    void emptyBatchDoesNotTouchPersistence() {
        service.removePostReferences(List.of());
        verifyNoInteractions(notifications, outbox);
    }
}
