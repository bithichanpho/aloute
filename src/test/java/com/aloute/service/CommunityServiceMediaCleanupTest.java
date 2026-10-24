package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommunityServiceMediaCleanupTest {
    @Test
    void ownerDeletionPersistsCleanupIntentBeforeRemovingSqlRows() {
        ICommunityRepository communities = mock(ICommunityRepository.class);
        ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
        CommunityCleanupOutboxService cleanupOutbox = mock(CommunityCleanupOutboxService.class);
        Community community = new Community();
        community.setId(17L);
        community.setAvatarUrl("avatar");
        community.setCoverUrl("cover");
        when(communities.findById(17L)).thenReturn(Optional.of(community));
        when(members.findByCommunityIdAndUserId(17L, 9L))
                .thenReturn(Optional.of(new CommunityMember(17L, 9L, CommunityService.ROLE_OWNER)));
        CommunityService service = new CommunityService(
                communities, members, mock(PostService.class), null, null,
                null, null, null, cleanupOutbox);

        service.deleteCommunity(17L, 9L);

        var order = inOrder(cleanupOutbox, members, communities);
        order.verify(cleanupOutbox).enqueue(17L, "avatar", "cover");
        order.verify(members).deleteByCommunityId(17L);
        order.verify(communities).delete(community);
    }

    @Test
    void lastMemberLeavingPersistsTheSameCleanupIntent() {
        ICommunityRepository communities = mock(ICommunityRepository.class);
        ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
        MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
        CommunityCleanupOutboxService cleanupOutbox = mock(CommunityCleanupOutboxService.class);
        Community community = new Community();
        community.setId(17L);
        community.setAvatarUrl("https://res.cloudinary.com/demo/image/upload/v1/aloute/community/a.png");
        community.setCoverUrl("https://res.cloudinary.com/demo/image/upload/v1/aloute/community/c.png");
        when(communities.findById(17L)).thenReturn(Optional.of(community));
        when(members.findByCommunityIdAndUserId(17L, 9L))
                .thenReturn(Optional.of(new CommunityMember(17L, 9L, CommunityService.ROLE_ADMIN)));
        when(members.countByCommunityIdAndRoleIn(eq(17L), anyCollection())).thenReturn(0L);

        CommunityService service = new CommunityService(
                communities, members, mock(PostService.class),
                mock(MediaUploadModerationService.class), lifecycle,
                null, null, null, cleanupOutbox);

        assertNull(service.leave(17L, 9L));

        verify(members).deleteByCommunityId(17L);
        verify(communities).delete(community);
        verify(cleanupOutbox).enqueue(
                17L, community.getAvatarUrl(), community.getCoverUrl());
        verifyNoInteractions(lifecycle);
    }
}
