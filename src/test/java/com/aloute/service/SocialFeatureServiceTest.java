package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.*;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.*;

class SocialFeatureServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final IFriendRepository friends = mock(IFriendRepository.class);
    private final ICloseFriendRepository closeFriends = mock(ICloseFriendRepository.class);
    private final SocialFeatureService service =
            new SocialFeatureService(
                    mongo,
                    access,
                    users,
                    friends,
                    closeFriends,
                    mock(PostService.class),
                    mock(ModerationService.class),
                    mock(AuthRateLimitService.class),
                    mock(ChatService.class),
                    mock(NotificationService.class));

    @Test
    void ordinaryUserCannotViewModerationQueue() {
        User user = new User();
        user.setId(1L);
        user.setRole(new Role("ROLE_USER"));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        assertThrows(ForbiddenException.class, () -> service.queue(1L));
        verifyNoInteractions(mongo);
    }

    @Test
    void blockRemovesBothFollowDirections() {
        when(users.existsById(2L)).thenReturn(true);
        Friend outgoing = new Friend(1L, 2L), incoming = new Friend(2L, 1L);
        when(friends.findByFollowerIdAndFollowedId(1L, 2L)).thenReturn(Optional.of(outgoing));
        when(friends.findByFollowerIdAndFollowedId(2L, 1L)).thenReturn(Optional.of(incoming));
        service.block(1L, 2L, true);
        verify(friends).delete(outgoing);
        verify(friends).delete(incoming);
        verify(mongo).save(argThat((UserBlock block) -> block.getId().equals("1:2")));
    }

    @Test
    void cannotBlockSelf() {
        assertThrows(BadRequestException.class, () -> service.block(1L, 1L, true));
    }

    @Test
    void followRequestCanOnlyBeDecidedByRecipient() {
        FollowRequest request = new FollowRequest();
        request.setTargetId(2L);
        request.setFollowerId(1L);
        when(mongo.findById("request", FollowRequest.class)).thenReturn(request);
        assertThrows(
                ResourceNotFoundException.class, () -> service.decideFollow(3L, "request", true));
        verifyNoInteractions(friends);
    }

    @Test
    void cannotEditOrReadHistoryOfSomeoneElsesPost() {
        Post post = new Post();
        post.setUserId("2");
        when(access.requirePost("post", 1L)).thenReturn(post);
        assertThrows(
                ForbiddenException.class,
                () -> service.edit(1L, "post", new CreatePostRequest()));
        assertThrows(ForbiddenException.class, () -> service.history(1L, "post"));
        verifyNoInteractions(mongo);
    }

    @Test
    void hiddenOrBlockedPostCannotBeSaved() {
        doThrow(new ResourceNotFoundException("hidden")).when(access).requirePost("post", 1L);
        assertThrows(ResourceNotFoundException.class, () -> service.save(1L, "post", true));
        verifyNoInteractions(mongo);
    }

    @Test
    void duplicateReportDoesNotResetReviewDecision() {
        ContentReport reviewed = new ContentReport();
        reviewed.setStatus("RESOLVED");
        when(mongo.findById("1:POST:post", ContentReport.class)).thenReturn(reviewed);
        assertSame(reviewed, service.report(1L, "POST", "post", "Spam"));
        verify(mongo, never()).insert(any(ContentReport.class));
    }

    @Test
    void storyIsModeratedAndExpiresAfterTwentyFourHours() {
        ModerationService moderationService = mock(ModerationService.class);
        AuthRateLimitService rateLimit = mock(AuthRateLimitService.class);
        SocialFeatureService storyService =
                new SocialFeatureService(
                        mongo,
                        access,
                        users,
                        friends,
                        closeFriends,
                        mock(PostService.class),
                        moderationService,
                        rateLimit,
                        mock(ChatService.class),
                        mock(NotificationService.class));
        when(moderationService.check("Một ngày đẹp trời"))
                .thenReturn(new com.aloute.dto.response.ModerationResult("safe", 0.01));
        when(moderationService.resolveStatus(0.01)).thenReturn(ModerationStatus.APPROVED);
        when(moderationService.isMediaApproved("https://cdn/story.jpg", "image")).thenReturn(true);
        when(moderationService.isMediaApproved(null, "video")).thenReturn(true);
        when(mongo.insert(any(Story.class))).thenAnswer(call -> call.getArgument(0));
        Instant before = Instant.now();

        Story story = storyService.story(1L, "Một ngày đẹp trời", "https://cdn/story.jpg", null);

        assertEquals(1L, story.getUserId());
        assertEquals("https://cdn/story.jpg", story.getImageUrl());
        assertTrue(story.getExpiresAt().isAfter(before.plusSeconds(86_390)));
        verify(rateLimit)
                .checkAndRecord(eq("STORY"), eq("1"), anyInt(), any(), any(), any(), any());
        verify(mongo).insert(story);
    }
}
