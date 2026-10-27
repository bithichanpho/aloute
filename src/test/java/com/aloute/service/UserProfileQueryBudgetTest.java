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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class UserProfileQueryBudgetTest {

    @Test
    void profileUsesOneUserReadAndOneAggregateRelationshipQuery() {
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        FriendService friendService = mock(FriendService.class);
        CloseFriendService closeFriends = mock(CloseFriendService.class);
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
        when(closeFriends.countCloseFriends(9L)).thenReturn(7L);
        when(closeFriends.getStatusMap(2L, List.of(9L))).thenReturn(Map.of(9L, "ACCEPTED"));

        UserService service = new UserService(
                users,
                friends,
                friendService,
                closeFriends,
                mock(SimpMessagingTemplate.class),
                access);

        var profile = service.getProfile(9L, 2L);

        assertEquals(120L, profile.getFollowerCount());
        assertEquals(40L, profile.getFollowingCount());
        assertEquals(7L, profile.getCloseFriendCount());
        assertTrue(profile.isFollowedByCurrentUser());
        assertTrue(profile.isMutualFriend());
        assertEquals("ACCEPTED", profile.getCloseFriendStatus());
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
        CloseFriendService closeFriends = mock(CloseFriendService.class);
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
        when(friendService.hasPendingFollowRequest(9L, 2L)).thenReturn(true);
        when(closeFriends.getStatusMap(2L, List.of(9L))).thenReturn(Map.of());

        UserService service = new UserService(
                users,
                friends,
                friendService,
                closeFriends,
                mock(SimpMessagingTemplate.class),
                access);

        var profile = service.getProfile(9L, 2L);

        assertTrue(profile.isFollowRequestPending());
        assertEquals("9:2",profile.getIncomingFollowRequestId());
        verify(friendService).hasPendingFollowRequest(2L, 9L);
        verify(friendService).hasPendingFollowRequest(9L, 2L);
    }
}
