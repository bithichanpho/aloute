package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.notifications.api.NotificationIntents;
import com.aloute.notifications.api.ReliableNotificationPush;
import com.aloute.notifications.application.NotificationApplicationService;
import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.repository.INotificationRepository;
import com.aloute.social.api.ProfileVisibility;
import com.aloute.users.api.UserDirectory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class NotificationApplicationServiceTest {
    private final INotificationRepository repository = mock(INotificationRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final PostNotificationLookup posts = mock(PostNotificationLookup.class);
    private final ProfileVisibility access = mock(ProfileVisibility.class);
    private final ReliableNotificationPush push = mock(ReliableNotificationPush.class);
    private final NotificationIntents outbox = mock(NotificationIntents.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final NotificationApplicationService service = new NotificationApplicationService(
            repository, users, posts, messaging, access, push, outbox);

    @Test
    void workerDirectoryBatchesDistinctActorAndReceiverIds() {
        var first = event("FOLLOW");
        var second = event("FOLLOW");
        var names = java.util.Map.of(1L, "alice", 2L, "bob");
        when(users.usernames(List.of(1L, 2L))).thenReturn(names);
        assertEquals(names, service.deliveryDirectory(List.of(first, second)));
        verify(users).usernames(List.of(1L, 2L));
        verify(users, never()).username(any());
    }

    @Test
    void batchedDeliveryDoesNotRepeatIndividualDirectoryLookups() {
        allowProfiles();
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        service.deliverQueued(event("FOLLOW"), java.util.Map.of(1L, "alice", 2L, "bob"));
        verifyNoInteractions(users);
        verify(messaging).convertAndSendToUser(eq("bob"), eq("/queue/notifications"), any());
    }

    @Test
    void retryReusesPersistedNotificationAndKeepsRealtimeContract() {
        NotificationOutboxEvent event = event("MENTION");
        allowProfiles();
        when(posts.isReadable("post", 2L)).thenReturn(true);
        when(users.username(1L)).thenReturn(Optional.of("alice"));
        when(users.username(2L)).thenReturn(Optional.of("bob"));
        Notification saved = new Notification();
        saved.setId(10L);
        saved.setContent("alice mentioned");
        when(repository.findByOutboxEventId(event.getId())).thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.deliverQueued(event);
        service.deliverQueued(event);

        verify(repository, times(1)).save(argThat(n -> event.getId().equals(n.getOutboxEventId())));
        verify(messaging, times(2)).convertAndSendToUser(eq("bob"), eq("/queue/notifications"), any());
        // External sends remain at-least-once; the persisted user-visible notification is deduped.
    }

    @Test
    void stalePostReferenceIsDroppedBeforeAnyDatabaseNotificationOrExternalSend() {
        allowProfiles();
        for (String type : List.of("LIKE", "COMMENT", "REPOST", "MENTION", "COMMUNITY_POST_OK", "COMMUNITY_POST_NO")) {
            service.deliverQueued(event(type));
        }
        verifyNoInteractions(repository, push, messaging, users);
    }

    @Test
    void demotedManagerCannotReceiveDelayedCommunityReviewNotification() {
        allowProfiles();
        service.deliverQueued(event("COMMUNITY_REVIEW"));
        verify(posts).canReviewCommunityPost("post", 2L);
        verifyNoInteractions(repository, push, messaging, users);
    }

    @Test
    void blockedOrInactiveSenderCannotDeliverQueuedContent() {
        service.deliverQueued(event("FOLLOW"));
        verifyNoInteractions(repository, push, messaging, users, posts);
    }

    @Test
    void selfAndBlockedRequestsNeverCreateDeliveryIntents() {
        service.notify(1L, 1L, "FOLLOW", null, "followed");
        service.notify(2L, 1L, "FOLLOW", null, "followed");
        verifyNoInteractions(outbox);
    }

    @Test
    void foreignNotificationCannotBeReadOrDeleted() {
        Notification notification = new Notification();
        notification.setReceiverId(3L);
        when(repository.findById(10L)).thenReturn(Optional.of(notification));
        assertThrows(ResourceNotFoundException.class, () -> service.markAsRead(2L, 10L));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(2L, 10L));
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any(Notification.class));
    }

    @Test
    void notificationPageUsesOneBatchVisibilityCheckAndRetainsCursor() {
        Notification first = new Notification();
        first.setId(9L);
        first.setSenderId(1L);
        Notification hidden = new Notification();
        hidden.setId(8L);
        hidden.setSenderId(3L);
        Notification extra = new Notification();
        extra.setId(7L);
        when(repository.findByReceiverIdOrderByCreatedAtDesc(eq(2L), any()))
                .thenReturn(List.of(first, hidden, extra));
        when(access.visibleProfileIds(2L, List.of(1L, 3L))).thenReturn(Set.of(1L));

        var page = service.getMyNotifications(2L, null, 2);

        assertEquals(List.of(9L), page.items().stream().map(n -> n.getId()).toList());
        assertEquals(8L, page.nextCursor());
        assertTrue(page.hasMore());
        verify(access).visibleProfileIds(2L, List.of(1L, 3L));
        verifyNoInteractions(users);
    }

    @Test
    void enqueueFailurePropagatesSoCallerCanRollBack() {
        allowProfiles();
        doThrow(new IllegalStateException("database unavailable")).when(outbox)
                .enqueue(2L, 1L, "FOLLOW", null, "followed");
        assertThrows(IllegalStateException.class,
                () -> service.notify(2L, 1L, "FOLLOW", null, "followed"));
    }

    private void allowProfiles() {
        when(access.profileVisible(2L, 1L)).thenReturn(true);
        when(access.profileVisible(1L, 2L)).thenReturn(true);
    }

    private NotificationOutboxEvent event(String type) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setReceiverId(2L);
        event.setSenderId(1L);
        event.setNotificationType(type);
        event.setReferenceId("post");
        event.setContent("mentioned");
        return event;
    }
}
