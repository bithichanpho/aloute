package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.Notification;
import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class NotificationServiceDeliveryTest {
    @Test
    void retriesExternalPushWithoutDuplicatingCheckpointedRealtimeDelivery() {
        Fixture fixture = fixture();
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary FCM outage"))
                .when(fixture.push)
                .sendNotificationToUserReliably(2L, "ALOUTE", "alice liked", "/activity");

        assertThrows(IllegalStateException.class, () -> fixture.service.deliverQueued(fixture.event));
        assertThrows(IllegalStateException.class, () -> fixture.service.deliverQueued(fixture.event));

        verify(fixture.messaging, org.mockito.Mockito.times(1))
                .convertAndSendToUser(any(String.class), any(String.class), any());
        verify(fixture.outbox).markInAppDelivered(eq(fixture.event.getId()), any());
    }

    @Test
    void checkpointsRealtimeBeforeAttemptingExternalPush() {
        Fixture fixture = fixture();

        fixture.service.deliverQueued(fixture.event);

        var order = inOrder(fixture.push, fixture.messaging, fixture.outbox);
        order.verify(fixture.messaging)
                .convertAndSendToUser(eq("bob"), eq("/queue/notifications"), any());
        order.verify(fixture.outbox).markInAppDelivered(eq(fixture.event.getId()), any());
        order.verify(fixture.push)
                .sendNotificationToUserReliably(2L, "ALOUTE", "alice liked", "/activity");
    }

    @Test
    void checkpointFailureDoesNotMarkTheEventDeliveredInMemoryOrProceedToPush() {
        Fixture fixture = fixture();
        org.mockito.Mockito.doThrow(new IllegalStateException("checkpoint unavailable"))
                .when(fixture.outbox).markInAppDelivered(eq(fixture.event.getId()), any());
        assertThrows(IllegalStateException.class, () -> fixture.service.deliverQueued(fixture.event));
        assertNull(fixture.event.getInAppDeliveredAt());
        verify(fixture.push, never()).sendNotificationToUserReliably(any(), any(), any(), any());
    }

    private Fixture fixture() {
        INotificationRepository notifications = mock(INotificationRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        IPostRepository posts = mock(IPostRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ContentAccessService access = mock(ContentAccessService.class);
        PushNotificationService push = mock(PushNotificationService.class);
        NotificationOutboxService outbox = mock(NotificationOutboxService.class);
        NotificationService service =
                new NotificationService(notifications, users, posts, messaging, access, push, outbox);

        when(access.profileVisible(2L, 1L)).thenReturn(true);
        when(access.profileVisible(1L, 2L)).thenReturn(true);
        User sender = new User();
        sender.setId(1L);
        sender.setUsername("alice");
        User receiver = new User();
        receiver.setId(2L);
        receiver.setUsername("bob");
        when(users.findById(1L)).thenReturn(Optional.of(sender));
        when(users.findById(2L)).thenReturn(Optional.of(receiver));

        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setSenderId(1L);
        event.setReceiverId(2L);
        event.setNotificationType("LIKE");
        event.setReferenceId("post");
        com.aloute.model.document.Post post = new com.aloute.model.document.Post();
        post.setUserId("2");
        when(posts.findById("post")).thenReturn(Optional.of(post));
        when(access.canRead(post, 2L)).thenReturn(true);
        event.setContent("liked");
        Notification saved = new Notification();
        saved.setSenderId(1L);
        saved.setReceiverId(2L);
        saved.setType("LIKE");
        saved.setContent("alice liked");
        when(notifications.findByOutboxEventId(event.getId())).thenReturn(Optional.of(saved));
        return new Fixture(service, messaging, push, event, outbox);
    }

    private record Fixture(
            NotificationService service,
            SimpMessagingTemplate messaging,
            PushNotificationService push,
            NotificationOutboxEvent event, NotificationOutboxService outbox) {}
}
