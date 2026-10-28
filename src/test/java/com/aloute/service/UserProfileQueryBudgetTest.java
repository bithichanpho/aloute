package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class UserProfileQueryBudgetTest {
    @Test
    void usernameLookupIsExactAndUsesTheSameProfileAuthorization() {
        var users = mock(IUserRepository.class); var friends = mock(IFriendRepository.class);
        var access = mock(ContentAccessService.class);
        var target = new User(); target.setId(9L); target.setUsername("alice.name"); target.setActive(true);
        when(users.findByUsernameIgnoreCase("ALICE.NAME")).thenReturn(Optional.of(target));
        var service = new UserService(users, friends, mock(FriendService.class), mock(SimpMessagingTemplate.class), access);
        assertEquals("alice.name", service.getProfileByUsername("ALICE.NAME", 9L).getUsername());
        verify(users).findByUsernameIgnoreCase("ALICE.NAME");
        verify(users, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        verify(access).requireProfile(9L, target);
        org.mockito.Mockito.doThrow(new com.aloute.common.exception.ForbiddenException("blocked"))
                .when(access).requireProfile(2L, target);
        org.junit.jupiter.api.Assertions.assertThrows(com.aloute.common.exception.ForbiddenException.class,
                () -> service.getProfileByUsername("ALICE.NAME", 2L));
        verify(friends, never()).getProfileRelationshipStats(9L, 2L);
        org.junit.jupiter.api.Assertions.assertThrows(com.aloute.common.exception.ResourceNotFoundException.class,
                () -> service.getProfileByUsername("missing", 2L));
    }


    @Test
    void profileUsesOneUserReadAndOneAggregateRelationshipQuery() {
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        FriendService friendService = mock(FriendService.class);
        ContentAccessService access = mock(ContentAccessService.class);
        IFriendRepository.ProfileRelationshipStats stats =
                mock(IFriendRepository.ProfileRelationshipStats.class);

        User target = new User();
        target.setId(9L);
        target.setUsername("target");
        target.setFullName("Target User");
        target.setActive(true);
        target.setPrivateAccount(true);
        when(users.findById(9L)).thenReturn(Optional.of(target));
        when(friends.getProfileRelationshipStats(9L, 2L)).thenReturn(stats);
        when(stats.followerCount()).thenReturn(120L);
        when(stats.followingCount()).thenReturn(40L);
        when(stats.viewerFollows()).thenReturn(true);
        when(stats.targetFollowsViewer()).thenReturn(true);

        UserService service = new UserService(
                users,
                friends,
                friendService,
                mock(SimpMessagingTemplate.class),
                access);

        var profile = service.getProfile(9L, 2L);

        assertEquals(120L, profile.getFollowerCount());
        assertEquals(40L, profile.getFollowingCount());
        assertTrue(profile.isFollowedByCurrentUser());
        assertTrue(profile.isMutualFriend());
        assertTrue(profile.isCanMessage());
        assertFalse(profile.isFollowRequestPending());

        verify(users).findById(9L);
        verify(access).requireProfile(2L, target);
        verify(friends).getProfileRelationshipStats(9L, 2L);
        verify(friends, never()).countByFollowedId(9L);
        verify(friends, never()).countByFollowerId(9L);
        verify(friends, never()).existsByFollowerIdAndFollowedId(2L, 9L);
        verify(friendService, never()).isFriend(2L, 9L);
        verify(friendService, never()).followStatus(2L, 9L);
        verify(friendService, never()).hasPendingFollowRequest(2L, 9L);
    }

    @Test
    void pendingRequestLookupRunsOnlyWhenViewerDoesNotAlreadyFollow() {
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        FriendService friendService = mock(FriendService.class);
        ContentAccessService access = mock(ContentAccessService.class);
        IFriendRepository.ProfileRelationshipStats stats =
                mock(IFriendRepository.ProfileRelationshipStats.class);

        User target = new User();
        target.setId(9L);
        target.setUsername("target");
        target.setActive(true);
        when(users.findById(9L)).thenReturn(Optional.of(target));
        when(friends.getProfileRelationshipStats(9L, 2L)).thenReturn(stats);
        when(stats.viewerFollows()).thenReturn(false);
        when(friendService.hasPendingFollowRequest(2L, 9L)).thenReturn(true);

        UserService service = new UserService(
                users,
                friends,
                friendService,
                mock(SimpMessagingTemplate.class),
                access);

        var profile = service.getProfile(9L, 2L);

        assertTrue(profile.isFollowRequestPending());
        verify(friendService).hasPendingFollowRequest(2L, 9L);
    }

    @Test
    void incomingRequestBelongsToViewerAndDoesNotReplaceOutgoingState() {
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        FriendService requests = mock(FriendService.class);
        ContentAccessService access = mock(ContentAccessService.class);
        var stats = mock(IFriendRepository.ProfileRelationshipStats.class);
        User target = new User(); target.setId(9L); target.setUsername("alice");
        when(users.findById(9L)).thenReturn(Optional.of(target));
        when(friends.getProfileRelationshipStats(9L, 2L)).thenReturn(stats);
        when(requests.hasPendingFollowRequest(9L, 2L)).thenReturn(true);
        UserService service = new UserService(users, friends, requests,
                mock(SimpMessagingTemplate.class), access);
        var profile = service.getProfile(9L, 2L);
        assertEquals("9:2", profile.getIncomingFollowRequestId());
        assertFalse(profile.isFollowRequestPending());
        when(stats.targetFollowsViewer()).thenReturn(true);
        assertEquals(null, service.getProfile(9L, 2L).getIncomingFollowRequestId());
    }

    @Test
    void blockedProfileDoesNotExposeIncomingRequest() {
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        FriendService requests = mock(FriendService.class);
        ContentAccessService access = mock(ContentAccessService.class);
        User target = new User(); target.setId(9L);
        when(users.findById(9L)).thenReturn(Optional.of(target));
        org.mockito.Mockito.doThrow(new com.aloute.common.exception.ResourceNotFoundException("blocked"))
                .when(access).requireProfile(2L, target);
        UserService service = new UserService(users, friends, requests,
                mock(SimpMessagingTemplate.class), access);
        org.junit.jupiter.api.Assertions.assertThrows(com.aloute.common.exception.ResourceNotFoundException.class,
                () -> service.getProfile(9L, 2L));
        org.mockito.Mockito.verifyNoInteractions(requests, friends);
    }
}
