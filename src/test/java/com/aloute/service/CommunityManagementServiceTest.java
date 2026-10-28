package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.ForbiddenException;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommunityManagementServiceTest {
    private final ICommunityRepository communities = mock(ICommunityRepository.class);
    private final ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
    private final PostService posts = mock(PostService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CommentService comments = mock(CommentService.class);
    private final CommunityService service = new CommunityService(
            communities, members, posts, null, null, users, notifications, comments);

    @BeforeEach
    void communityExists() {
        Community community = new Community();
        community.setId(7L);
        community.setName("Spring Boot Việt Nam");
        community.setPrivacy(CommunityService.PRIVACY_PUBLIC);
        when(communities.findById(7L)).thenReturn(Optional.of(community));
    }

    @Test
    void memberPostWaitsForCommunityReviewAndManagersAreNotified() {
        when(members.findByCommunityIdAndUserId(7L, 20L))
                .thenReturn(Optional.of(new CommunityMember(7L, 20L, CommunityService.ROLE_MEMBER)));
        when(members.findByCommunityIdAndRoleInOrderByJoinedAtAsc(
                        eq(7L), anyCollection(), any()))
                .thenReturn(java.util.List.of(
                        new CommunityMember(7L, 10L, CommunityService.ROLE_OWNER)));
        PostResponse created = new PostResponse();
        created.setId("post-1");
        created.setCommunityPostStatus(CommunityPostStatus.PENDING);
        when(posts.createPost(eq(20L), any(), eq("7"), eq(CommunityPostStatus.PENDING)))
                .thenReturn(created);

        PostResponse result = service.createPost(7L, 20L, new CreatePostRequest());

        assertEquals(CommunityPostStatus.PENDING, result.getCommunityPostStatus());
        verify(notifications).notify(
                10L, 20L, "COMMUNITY_REVIEW", "post-1",
                "đã gửi một bài viết chờ duyệt trong cộng đồng");
    }

    @Test
    void managerPostIsApprovedImmediately() {
        when(members.findByCommunityIdAndUserId(7L, 11L))
                .thenReturn(Optional.of(new CommunityMember(7L, 11L, CommunityService.ROLE_MANAGER)));
        PostResponse created = new PostResponse();
        created.setCommunityPostStatus(CommunityPostStatus.APPROVED);
        when(posts.createPost(eq(11L), any(), eq("7"), eq(CommunityPostStatus.APPROVED)))
                .thenReturn(created);

        assertEquals(CommunityPostStatus.APPROVED,
                service.createPost(7L, 11L, new CreatePostRequest()).getCommunityPostStatus());
        verifyNoInteractions(notifications);
    }

    @Test
    void onlyOwnerCanPromoteManager() {
        CommunityMember owner = new CommunityMember(7L, 10L, CommunityService.ROLE_OWNER);
        CommunityMember target = new CommunityMember(7L, 20L, CommunityService.ROLE_MEMBER);
        when(members.findByCommunityIdAndUserId(7L, 10L)).thenReturn(Optional.of(owner));
        when(members.findByCommunityIdAndUserId(7L, 20L)).thenReturn(Optional.of(target));

        service.setManager(7L, 10L, 20L, true);

        assertEquals(CommunityService.ROLE_MANAGER, target.getRole());
        verify(members).save(target);
    }

    @Test
    void leadershipRequiresMembershipForPrivateGroup() {
        Community privateCommunity = new Community();
        privateCommunity.setId(7L);
        privateCommunity.setName("Nhóm riêng tư");
        privateCommunity.setPrivacy(CommunityService.PRIVACY_PRIVATE);
        when(communities.findById(7L)).thenReturn(Optional.of(privateCommunity));
        when(members.existsByCommunityIdAndUserIdAndRoleIn(eq(7L), eq(20L), anyCollection()))
                .thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.leadership(7L, 20L));
        verify(members, never()).findByCommunityIdAndRoleInOrderByJoinedAtAsc(eq(7L), anyCollection(), any());
    }

    @Test
    void regularMemberCannotReadModerationQueue() {
        when(members.findByCommunityIdAndUserId(7L, 20L))
                .thenReturn(Optional.of(new CommunityMember(7L, 20L, CommunityService.ROLE_MEMBER)));

        assertThrows(ForbiddenException.class, () -> service.pendingPosts(7L, 20L, 0, 20));
        verifyNoInteractions(posts);
    }

    @Test
    void managerCanApproveOnlyPostInsideTheirCommunity() {
        when(members.findByCommunityIdAndUserId(7L, 11L))
                .thenReturn(Optional.of(new CommunityMember(7L, 11L, CommunityService.ROLE_MANAGER)));
        when(posts.decideCommunityPost(
                "foreign", "7", 11L, CommunityPostStatus.APPROVED))
                .thenThrow(new com.aloute.common.exception.BadRequestException(
                        "Bài viết không tồn tại trong cộng đồng hoặc không còn chờ duyệt"));

        assertThrows(com.aloute.common.exception.BadRequestException.class,
                () -> service.decidePost(7L, 11L, "foreign", true));
        verify(posts).decideCommunityPost("foreign", "7", 11L, CommunityPostStatus.APPROVED);
    }
}
