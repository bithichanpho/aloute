package com.aloute.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.User;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import com.aloute.service.*;
import com.aloute.users.api.ProfileLinks;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class UsernameProfileTest {
    final IUserRepository users = mock(IUserRepository.class);
    final IFriendRepository follows = mock(IFriendRepository.class);
    final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    final ContentAccessService access = new ContentAccessService(store, users, follows,
            mock(ICommunityRepository.class), mock(ICommunityMemberRepository.class));
    final UserService service = new UserService(users, follows, mock(FriendService.class), mock(CloseFriendService.class), mock(SimpMessagingTemplate.class), access);

    @Test void handlesAreLocalEncodedPathsWithSafeLegacyFallbacks() {
        assertEquals("/@casotoichoi", ProfileLinks.path("casotoichoi", 26L));
        assertEquals("/@alice.name-1", ProfileLinks.path("alice.name-1", 26L));
        assertEquals("/@alice%40example.com", ProfileLinks.path("alice@example.com", 26L));
        assertEquals("/@123", ProfileLinks.path("123", 26L));
        for (String invalid : Arrays.asList(null,"","a/b","a?b","a#b","javascript:bad","deleted_1","deleted~1","a\nb"))
            assertEquals("/profile/26", ProfileLinks.path(invalid, 26L));
        assertEquals("#", ProfileLinks.path(null, null));
    }

    @Test void exactCaseInsensitiveLookupUsesOneUserReadAndKeepsPrivateProfileMetadata() {
        var target = user(); when(users.findByUsernameIgnoreCase("CaSo")).thenReturn(Optional.of(target));
        var profile = service.getProfileByUsername("CaSo", 2L);
        assertEquals(26L, profile.getId()); assertEquals("caso", profile.getUsername());
        assertTrue(profile.isPrivateAccount()); assertFalse(profile.isFollowedByCurrentUser());
        verify(users).findByUsernameIgnoreCase("CaSo"); verify(users, never()).findById(anyLong());
        verify(follows).getProfileRelationshipStats(26L, 2L);
        when(users.findById(26L)).thenReturn(Optional.of(target));
        assertFalse(access.profileContentVisible(2L, 26L));
        when(follows.existsByFollowerIdAndFollowedId(2L, 26L)).thenReturn(true);
        assertTrue(access.profileContentVisible(2L, 26L));
    }

    @Test void missingReservedAndUnsafeHandlesNeverFallBackToFuzzySearch() {
        assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("missing", 2L));
        for (String invalid : List.of("a/b", "deleted_1", "javascript:bad"))
            assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername(invalid, 2L));
        verify(users).findByUsernameIgnoreCase("missing"); verifyNoMoreInteractions(users);
        verifyNoInteractions(follows);
    }

    @Test void inactiveAndBlockedUsersAreDeniedBeforeRelationshipDetails() {
        var target = user(); target.setActive(false);
        when(users.findByUsernameIgnoreCase("caso")).thenReturn(Optional.of(target));
        assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("caso", 2L));
        target.setActive(true);
        when(store.exists(any(Query.class), eq(com.aloute.model.document.UserBlock.class))).thenReturn(true);
        assertThrows(ResourceNotFoundException.class, () -> service.getProfileByUsername("caso", 2L));
        verifyNoInteractions(follows);
    }

    private User user() {
        var target = new User(); target.setId(26L); target.setUsername("caso"); target.setFullName("Caso");
        target.setActive(true); target.setPrivateAccount(true); return target;
    }
}
