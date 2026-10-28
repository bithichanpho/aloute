package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.CloseFriend;
import com.aloute.notifications.api.Notifications;
import com.aloute.repository.ICloseFriendRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CloseFriendPrivacyTest {
    final ICloseFriendRepository relationships = mock(ICloseFriendRepository.class);
    final FriendService follows = mock(FriendService.class);
    final Notifications notifications = mock(Notifications.class);
    final ContentAccessService access = mock(ContentAccessService.class);
    final CloseFriendService service = new CloseFriendService(relationships, follows, notifications, access);

    @Test
    void requestNeedsMutualFollowingAndDoesNotWriteOnFailure() {
        assertThrows(BadRequestException.class, () -> service.sendRequest(1L, 2L));
        verify(access).requireProfile(1L, 2L);
        verifyNoInteractions(relationships, notifications);
    }

    @Test
    void blockedOrInactiveTargetIsRejectedBeforeReadingRelationships() {
        doThrow(new ResourceNotFoundException("hidden")).when(access).requireProfile(1L, 2L);
        assertThrows(ResourceNotFoundException.class, () -> service.sendRequest(1L, 2L));
        verifyNoInteractions(follows, relationships, notifications);
    }

    @Test
    void duplicateAcceptAfterConcurrentWinnerDoesNotNotifyTwice() {
        var pending = new CloseFriend(1L, 2L);
        var accepted = new CloseFriend(1L, 2L); accepted.setStatus(CloseFriend.ACCEPTED);
        when(follows.isFriend(2L, 1L)).thenReturn(true);
        when(relationships.findByRequesterIdAndReceiverId(1L, 2L))
                .thenReturn(Optional.of(pending), Optional.of(pending), Optional.of(accepted));
        when(relationships.acceptPending(eq(1L), eq(2L), any())).thenReturn(1, 0);
        assertEquals("ACCEPTED", service.acceptRequest(2L, 1L));
        assertEquals("ACCEPTED", service.acceptRequest(2L, 1L));
        verify(notifications, times(1)).notify(1L, 2L, "CLOSE_FRIEND_ACCEPTED", "2",
                "đã chấp nhận lời mời kết bạn thân của bạn");
        verify(relationships, never()).save(any());
    }

    @Test
    void unrelatedUserCannotAcceptAnotherRecipientsRequest() {
        when(follows.isFriend(3L, 1L)).thenReturn(true);
        assertThrows(ResourceNotFoundException.class, () -> service.acceptRequest(3L, 1L));
        verify(relationships).findByRequesterIdAndReceiverId(1L, 3L);
        verify(relationships, never()).acceptPending(any(), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void privateListRequiresContentAccessBeforeAnyRelationshipRead() {
        assertThrows(ResourceNotFoundException.class, () -> service.requireListAccess(2L, 1L));
        verify(access).requireProfile(2L, 1L);
        verify(access).canViewAccountContent(2L, 1L);
        verifyNoInteractions(relationships);
    }

    @Test
    void removingBlockedRelationshipStillAllowsOwnerToRevokeIt() {
        var relationship = new CloseFriend(1L, 2L);
        when(relationships.findByRequesterIdAndReceiverId(1L, 2L)).thenReturn(Optional.of(relationship));
        service.removeRelationship(2L, 1L);
        verify(relationships).findByRequesterIdAndReceiverId(2L, 1L);
        verify(relationships).delete(relationship);
        verifyNoInteractions(access, notifications);
    }
}
