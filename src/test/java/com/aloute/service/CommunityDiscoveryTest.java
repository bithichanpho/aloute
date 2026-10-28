package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class CommunityDiscoveryTest {
    private final ICommunityRepository communities = mock(ICommunityRepository.class);
    private final ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
    private final CommunityService service = new CommunityService(communities, members, mock(PostService.class));

    @Test
    void discoveryExcludesAllJoinedRolesBeforeTheLimitAndKeepsMyGroups() {
        Community joined = community(7L, "Nhóm đã tham gia");
        Community other = community(8L, "Nhóm để khám phá");
        var roles = List.of("OWNER", "MANAGER", "ADMIN", "MEMBER");
        when(communities.findDiscoverableForUser("PUBLIC", 1L, roles, PageRequest.of(0, 30)))
                .thenReturn(List.of(other));
        when(members.findByUserIdAndRoleInOrderByJoinedAtDesc(eq(1L), eq(roles), any()))
                .thenReturn(List.of(new CommunityMember(7L, 1L, "MEMBER")));
        when(communities.findByIdIn(List.of(7L))).thenReturn(List.of(joined));

        assertEquals(List.of(8L), service.getDiscoverList(1L, 30).stream().map(c -> c.getId()).toList());
        var mine = service.getMyCommunities(1L);
        assertEquals(List.of(7L), mine.stream().map(c -> c.getId()).toList());
        assertTrue(mine.getFirst().isMember());
        verify(communities, never()).findByPrivacyOrderByMemberCountDesc(anyString(), any());
    }

    @Test
    void explicitSearchStillFindsAnAlreadyJoinedGroup() {
        when(communities.findByNameContainingIgnoreCaseOrderByMemberCountDesc("Nhóm", PageRequest.of(0, 10)))
                .thenReturn(List.of(community(7L, "Nhóm đã tham gia")));
        when(members.findByUserIdAndCommunityIdIn(1L, List.of(7L)))
                .thenReturn(List.of(new CommunityMember(7L, 1L, "OWNER")));
        var result = service.searchCommunities("Nhóm", 1L, 10);
        assertEquals(7L, result.getFirst().getId());
        assertTrue(result.getFirst().isMember());
        assertTrue(result.getFirst().isOwner());
    }

    @Test
    void explicitSearchCanFindPrivateGroupsButDoesNotImplyMembership() {
        Community privateGroup = community(9L, "Nhóm thực tập riêng tư");
        privateGroup.setPrivacy("PRIVATE");
        when(communities.findByNameContainingIgnoreCaseOrderByMemberCountDesc(
                "thực tập", PageRequest.of(0, 10)))
                .thenReturn(List.of(privateGroup));
        when(members.findByUserIdAndCommunityIdIn(2L, List.of(9L))).thenReturn(List.of());

        var result = service.searchCommunities("thực tập", 2L, 10);
        assertEquals(1, result.size());
        assertEquals("PRIVATE", result.getFirst().getPrivacy());
        assertFalse(result.getFirst().isMember());
    }

    private Community community(Long id, String name) {
        Community value = new Community();
        value.setId(id);
        value.setName(name);
        value.setPrivacy("PUBLIC");
        return value;
    }
}
