package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aloute.dto.request.CreatePostRequest;
import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;

/**
 * A new post must never be published as APPROVED by the create path itself. While moderation is
 * enabled it is PENDING and handed to the moderation queue; only the worker may approve it.
 */
class PostCreationModerationTest {
    private final IPostRepository postRepository = mock(IPostRepository.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final ModerationService moderation = mock(ModerationService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final PostModerationCoordinator coordinator = mock(PostModerationCoordinator.class);

    private final PostService service =
            new PostService(
                    postRepository,
                    users,
                    mock(ILikeRepository.class),
                    moderation,
                    notifications,
                    mock(PostgresDocumentStore.class),
                    mock(ICommentRepository.class),
                    mock(INotificationRepository.class),
                    mock(IRepostRepository.class),
                    access,
                    mock(RepostPresentationService.class),
                    mock(PostResponseMapper.class),
                    coordinator,
                    mock(MediaAssetLifecycleService.class),
                    mock(FeedCacheInvalidator.class),
                    mock(PostgresTransactionRetryExecutor.class),
                    mock(IFriendRepository.class));

    @BeforeEach
    void persistWithGeneratedId() {
        when(postRepository.save(any(Post.class)))
                .thenAnswer(
                        invocation -> {
                            Post post = invocation.getArgument(0);
                            post.setId("post-1");
                            return post;
                        });
        User bob = new User();
        bob.setId(2L);
        bob.setUsername("bob");
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(bob));
        when(access.canRead(any(Post.class), eq(2L))).thenReturn(true);
    }

    @Test
    void enabledModerationCreatesPendingPostAndQueuesIt() {
        when(moderation.isEnabled()).thenReturn(true);

        service.createPost(1L, request("hello @bob"));

        Post saved = savedPost();
        assertEquals(ModerationStatus.PENDING, saved.getModerationStatus());
        assertNotEquals(ModerationStatus.APPROVED, saved.getModerationStatus());
        assertEquals(0.0, saved.getToxicityScore());
        verify(coordinator).submit("post-1");
    }

    @Test
    void mentionsAreDeferredUntilTheWorkerApprovesThePost() {
        when(moderation.isEnabled()).thenReturn(true);

        service.createPost(1L, request("hello @bob"));

        // The worker delivers the notification after APPROVED, driven by this flag.
        assertTrue(savedPost().isNotifyMentionsAfterModeration());
        // Even though bob could read it, nothing is sent for an unreviewed post.
        verifyNoInteractions(notifications);
    }

    @Test
    void queueFailureNeverFailsOrApprovesTheCreatedPost() {
        when(moderation.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("queue down")).when(coordinator).submit("post-1");

        assertDoesNotThrow(() -> service.createPost(1L, request("hello")));

        // Scheduled recovery picks up PENDING posts; the post must not fall back to APPROVED.
        assertEquals(ModerationStatus.PENDING, savedPost().getModerationStatus());
    }

    @Test
    void communityPostStaysPendingForBothModerationAndCommunityReview() {
        when(moderation.isEnabled()).thenReturn(true);

        service.createPost(1L, request("hello"), "7", CommunityPostStatus.PENDING);

        Post saved = savedPost();
        assertEquals(ModerationStatus.PENDING, saved.getModerationStatus());
        assertEquals(CommunityPostStatus.PENDING, saved.getCommunityPostStatus());
        assertEquals("7", saved.getCommunityId());
        verify(coordinator).submit("post-1");
    }

    @Test
    void managerCommunityPostStillRequiresModerationDecision() {
        when(moderation.isEnabled()).thenReturn(true);

        service.createPost(1L, request("hello"), "7", CommunityPostStatus.APPROVED);

        // Community approval is an independent gate and must not imply content approval.
        assertEquals(ModerationStatus.PENDING, savedPost().getModerationStatus());
    }

    @Test
    void disabledModerationPublishesDirectlyOnlyForLocalDemoMode() {
        when(moderation.isEnabled()).thenReturn(false);

        service.createPost(1L, request("hello @bob"));

        Post saved = savedPost();
        assertEquals(ModerationStatus.APPROVED, saved.getModerationStatus());
        assertFalse(saved.isNotifyMentionsAfterModeration());
        verify(coordinator, never()).submit(anyString());
        verify(notifications)
                .notify(eq(2L), eq(1L), eq("MENTION"), eq("post-1"), anyString());
    }

    private Post savedPost() {
        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        return captor.getValue();
    }

    private static CreatePostRequest request(String content) {
        CreatePostRequest request = new CreatePostRequest();
        request.setContent(content);
        return request;
    }
}
