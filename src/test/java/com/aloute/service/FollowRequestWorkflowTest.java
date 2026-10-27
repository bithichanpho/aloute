package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.model.document.FollowRequest;
import com.aloute.model.entity.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FollowRequestWorkflowTest {
    @Test
    void privateRequestQueuesIntentAndCancelDoesNotCreateFollowOrAnotherIntent() {
        var store = mock(PostgresDocumentStore.class); var users = mock(IUserRepository.class);
        var friends = mock(IFriendRepository.class); var notifications = mock(Notifications.class);
        var recipient = new User(); recipient.setId(2L); recipient.setPrivateAccount(true);
        when(users.findById(2L)).thenReturn(Optional.of(recipient));
        var service = new FriendService(friends, notifications, mock(ContentAccessService.class), store, users);
        assertFalse(service.toggleFollow(1L, 2L));
        var saved = org.mockito.ArgumentCaptor.forClass(FollowRequest.class);
        verify(store).save(saved.capture());
        var request = saved.getValue();
        verify(notifications).notify(2L, 1L, "FOLLOW_REQUEST", "1:2|" + request.getCreatedAt(),
                "đã gửi yêu cầu theo dõi bạn");
        verify(friends, never()).save(any());
        when(store.findById("1:2", FollowRequest.class)).thenReturn(request);
        clearInvocations(notifications);
        assertFalse(service.toggleFollow(1L, 2L));
        verify(store).remove(request); verifyNoInteractions(notifications);
    }

    @Test
    void lookupRejectsAcceptedAndCanceledGenerationsWithOneBulkRelationshipQuery() {
        var store = mock(PostgresDocumentStore.class); var friends = mock(IFriendRepository.class);
        var access = new ContentAccessService(store, mock(IUserRepository.class), friends,
                mock(ICommunityRepository.class), mock(ICommunityMemberRepository.class));
        var request = request("1:2", 1L, 2L); var accepted = request("3:2", 3L, 2L);
        when(store.find(any(Query.class), eq(FollowRequest.class))).thenReturn(List.of(request, accepted));
        when(friends.findByFollowerIdInAndFollowedIdIn(List.of(1L, 3L), List.of(2L)))
                .thenReturn(List.of(new Friend(3L, 2L)));
        String current = "1:2|" + request.getCreatedAt(), old = "1:2|" + request.getCreatedAt().minusSeconds(1);
        assertEquals(Set.of("1:2", current), access.pendingFollowRequestIds(List.of("1:2", current, old, "3:2", "4:2")));
        verify(store, times(1)).find(any(Query.class), eq(FollowRequest.class));
        verify(friends, times(1)).findByFollowerIdInAndFollowedIdIn(any(), any());
        verify(friends, never()).existsByFollowerIdAndFollowedId(any(), any());
    }

    private FollowRequest request(String id, Long follower, Long target) {
        var request = new FollowRequest(); request.setId(id); request.setFollowerId(follower);
        request.setTargetId(target); request.setCreatedAt(Instant.parse("2026-10-25T08:00:00Z")); return request;
    }
}
