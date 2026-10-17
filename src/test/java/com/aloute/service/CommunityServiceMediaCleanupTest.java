package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommunityServiceMediaCleanupTest {
    @Test
    void lastMemberLeavingDeletesCommunityMediaThroughLifecycleOwner() {
        ICommunityRepository communities = mock(ICommunityRepository.class);
        ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
        MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
        Community community = new Community();
        community.setId(17L);
        community.setAvatarUrl("https://res.cloudinary.com/demo/image/upload/v1/aloute/community/a.png");
        community.setCoverUrl("https://res.cloudinary.com/demo/image/upload/v1/aloute/community/c.png");
        when(communities.findById(17L)).thenReturn(Optional.of(community));
        when(members.findByCommunityIdAndUserId(17L, 9L))
                .thenReturn(Optional.of(new CommunityMember(17L, 9L, CommunityService.ROLE_ADMIN)));
        when(members.findByCommunityIdOrderByJoinedAtAsc(17L)).thenReturn(List.of());

        CommunityService service = new CommunityService(
                communities, members, mock(PostService.class),
                mock(MediaUploadModerationService.class), lifecycle);

        assertNull(service.leave(17L, 9L));

        verify(members).deleteByCommunityId(17L);
        verify(communities).delete(community);
        verify(lifecycle).deleteIfUnreferenced(community.getAvatarUrl());
        verify(lifecycle).deleteIfUnreferenced(community.getCoverUrl());
    }
}
