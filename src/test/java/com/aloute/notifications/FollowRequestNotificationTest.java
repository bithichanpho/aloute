package com.aloute.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.model.entity.*;
import com.aloute.notifications.api.*;
import com.aloute.notifications.application.NotificationApplicationService;
import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.repository.INotificationRepository;
import com.aloute.social.api.*;
import com.aloute.users.api.UserDirectory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class FollowRequestNotificationTest {
    final INotificationRepository repository = mock(INotificationRepository.class);
    final UserDirectory users = mock(UserDirectory.class);
    final ProfileVisibility visibility = mock(ProfileVisibility.class);
    final FollowRequestLookup requests = mock(FollowRequestLookup.class);
    final ReliableNotificationPush push = mock(ReliableNotificationPush.class);
    final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    final NotificationApplicationService service = new NotificationApplicationService(repository, users,
            mock(PostNotificationLookup.class), messaging, visibility, push, mock(NotificationIntents.class), requests);

    @Test
    void listAddsPublicAvatarAndActionsOnlyWhileThatGenerationIsPending() {
        var notification = notification();
        when(repository.findByReceiverIdOrderByCreatedAtDesc(eq(2L), any())).thenReturn(List.of(notification));
        when(visibility.visibleProfileIds(2L, List.of(1L))).thenReturn(Set.of(1L));
        when(users.profileLabels(List.of(1L))).thenReturn(Map.of(1L,new UserDirectory.ProfileLabel("alice","Alice","/alice.png")));
        when(requests.pendingIds(List.of(notification.getReferenceId())))
                .thenReturn(Set.of(notification.getReferenceId()), Set.of());
        var dto = service.getMyNotifications(2L).getFirst();
        assertEquals(1L, dto.getSenderId()); assertEquals("/alice.png",dto.getSenderAvatarUrl());
        assertEquals("1:2",dto.getFollowRequestId());
        assertNull(service.getMyNotifications(2L).getFirst().getFollowRequestId());
        verify(users,times(2)).profileLabels(List.of(1L)); verify(users,never()).username(any());
    }

    @Test
    void staleCanceledDeliveryCannotInsertNotifyOrPush() {
        allow(); service.deliverQueued(event(),Map.of(1L,"alice",2L,"bob"),Set.of());
        verifyNoInteractions(repository,push,messaging,users);
    }

    @Test
    void retryReusesNotificationAndRealtimeResponseCarriesCanonicalActionId() {
        allow(); var event=event();
        when(repository.findByOutboxEventId(event.getId())).thenReturn(Optional.empty(),Optional.of(notification()));
        when(repository.save(any())).thenAnswer(call->call.getArgument(0));
        for(int i=0;i<2;i++) service.deliverQueued(event,Map.of(1L,"alice",2L,"bob"),Set.of(event.getReferenceId()));
        verify(repository,times(1)).save(any(Notification.class));
        var payload=org.mockito.ArgumentCaptor.forClass(com.aloute.dto.response.NotificationResponse.class);
        verify(messaging,times(2)).convertAndSendToUser(eq("bob"),eq("/queue/notifications"),payload.capture());
        assertTrue(payload.getAllValues().stream().allMatch(n->"1:2".equals(n.getFollowRequestId())));
        verifyNoInteractions(users,requests);
    }

    @Test
    void legacyFollowHistoryGetsActionsForItsCurrentlyPendingRequest() {
        var n=notification(); n.setType("FOLLOW"); n.setReferenceId(null);
        when(repository.findByReceiverIdOrderByCreatedAtDesc(eq(2L),any())).thenReturn(List.of(n));
        when(visibility.visibleProfileIds(2L,List.of(1L))).thenReturn(Set.of(1L));
        when(requests.pendingIds(List.of("1:2"))).thenReturn(Set.of("1:2"));
        assertEquals("1:2",service.getMyNotifications(2L).getFirst().getFollowRequestId());
    }

    @Test
    void deliveryPreparationBatchesRequestGenerationLookups() {
        var first=event(); var second=event(); second.setReferenceId("3:2|2026-10-25T08:00:00Z");
        service.pendingDeliveryRequests(List.of(first,second));
        verify(requests).pendingIds(List.of(first.getReferenceId(),second.getReferenceId()));
        verifyNoMoreInteractions(requests);
    }

    void allow() { when(visibility.profileVisible(2L,1L)).thenReturn(true); when(visibility.profileVisible(1L,2L)).thenReturn(true); }
    Notification notification() {
        var n=new Notification(); n.setId(10L); n.setSenderId(1L); n.setReceiverId(2L);
        n.setType("FOLLOW_REQUEST"); n.setReferenceId("1:2|2026-10-25T08:00:00Z");
        n.setContent("alice đã gửi yêu cầu theo dõi bạn"); return n;
    }
    NotificationOutboxEvent event() {
        var e=new NotificationOutboxEvent(); e.setSenderId(1L); e.setReceiverId(2L);
        e.setNotificationType("FOLLOW_REQUEST"); e.setReferenceId("1:2|2026-10-25T08:00:00Z");
        e.setContent("đã gửi yêu cầu theo dõi bạn"); return e;
    }
}
