package com.aloute.communities;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.communities.domain.CommunityRoles;
import com.aloute.communities.infrastructure.JpaCommunityReviewAccess;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommunityReviewAccessTest {
    @Test
    void currentMembershipPolicyPreservesOwnerManagerAndLegacyAdminOnly() {
        for (String role : List.of("OWNER", "MANAGER", "ADMIN", "MEMBER", "PENDING", "BANNED")) {
            assertEquals(List.of("OWNER", "MANAGER", "ADMIN").contains(role), CommunityRoles.canManage(role));
        }
        assertFalse(CommunityRoles.canManage(null));
    }

    @Test
    void delayedReviewFailsClosedAfterDemotionLeaveOrCommunityRemoval() {
        var communities = mock(ICommunityRepository.class);
        var members = mock(ICommunityMemberRepository.class);
        var access = new JpaCommunityReviewAccess(communities, members);
        when(communities.existsById(42L)).thenReturn(true);
        when(members.existsByCommunityIdAndUserIdAndRoleIn(42L, 7L, CommunityRoles.managerRoles()))
                .thenReturn(true, false);
        assertTrue(access.mayReviewPosts("42", 7L));
        assertFalse(access.mayReviewPosts("42", 7L));
        when(communities.existsById(42L)).thenReturn(false);
        clearInvocations(members);
        assertFalse(access.mayReviewPosts("42", 7L));
        verifyNoInteractions(members);
    }

    @Test
    void malformedIdsCannotQueryAnotherCommunity() {
        var communities = mock(ICommunityRepository.class);
        var members = mock(ICommunityMemberRepository.class);
        var access = new JpaCommunityReviewAccess(communities, members);
        assertFalse(access.mayReviewPosts("not-an-id", 7L));
        assertFalse(access.mayReviewPosts("42", null));
        verifyNoInteractions(communities, members);
    }
}
