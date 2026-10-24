package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.persistence.postgres.document.UpdateResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;

/** The worker is the only component allowed to move a PENDING post to a final decision. */
class PostModerationWorkerTest {
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final ModerationService moderation = mock(ModerationService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final PostModerationWorker worker =
            new PostModerationWorker(
                    documentStore,
                    moderation,
                    new ModerationRetryPolicy(5, 30),
                    mock(CacheManager.class),
                    users,
                    access,
                    notifications);

    @BeforeEach
    void defaults() {
        when(documentStore.updateFirst(any(Query.class), any(Update.class), eq(Post.class)))
                .thenReturn(new UpdateResult(1L, 1L));
        when(moderation.isMediaApproved(any(), anyString())).thenReturn(true);
        when(moderation.check("hello @bob")).thenReturn(new ModerationResult("safe", 0.1));
        when(moderation.resolveStatus(any(ModerationResult.class)))
                .thenReturn(ModerationStatus.APPROVED);
        User bob = new User();
        bob.setId(2L);
        bob.setUsername("bob");
        when(users.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(bob));
        when(access.canRead(any(Post.class), eq(2L))).thenReturn(true);
    }

    @Test
    void safePostIsApprovedByTheWorkerAndDeliversDeferredMentions() {
        when(documentStore.findById("post-1", Post.class)).thenReturn(pendingPost());

        worker.moderate("post-1").join();

        assertEquals(ModerationStatus.APPROVED, finalStatus());
        verify(notifications)
                .notify(eq(2L), eq(1L), eq("MENTION"), eq("post-1"), anyString());
    }

    @Test
    void flaggedPostIsNotApprovedAndSendsNoMentions() {
        when(documentStore.findById("post-1", Post.class)).thenReturn(pendingPost());
        when(moderation.resolveStatus(any(ModerationResult.class)))
                .thenReturn(ModerationStatus.FLAGGED);

        worker.moderate("post-1").join();

        assertEquals(ModerationStatus.FLAGGED, finalStatus());
        verifyNoInteractions(notifications);
    }

    @Test
    void toxicPostIsHidden() {
        when(documentStore.findById("post-1", Post.class)).thenReturn(pendingPost());
        when(moderation.resolveStatus(any(ModerationResult.class)))
                .thenReturn(ModerationStatus.HIDDEN);

        worker.moderate("post-1").join();

        assertEquals(ModerationStatus.HIDDEN, finalStatus());
        verifyNoInteractions(notifications);
    }

    @Test
    void unsafeMediaHidesThePostEvenWhenTheTextIsSafe() {
        Post post = pendingPost();
        post.setImageUrl("https://res.cloudinary.com/demo/image/upload/bad.png");
        when(documentStore.findById("post-1", Post.class)).thenReturn(post);
        when(moderation.isMediaApproved(post.getImageUrl(), "image")).thenReturn(false);

        worker.moderate("post-1").join();

        assertEquals(ModerationStatus.HIDDEN, finalStatus());
    }

    @Test
    void permanentModerationFailureFlagsForReviewInsteadOfApproving() {
        when(documentStore.findById("post-1", Post.class)).thenReturn(pendingPost());
        when(moderation.check("hello @bob"))
                .thenThrow(ModerationFailureException.permanentFailure("BAD_INPUT", "rejected", null));

        worker.moderate("post-1").join();

        Map<String, Object> set = lastUpdateSection("$set");
        assertEquals(ModerationStatus.FLAGGED, set.get("moderationStatus"));
        assertNotNull(set.get("moderationFailureReason"));
        verifyNoInteractions(notifications);
    }

    @Test
    void transientFailureKeepsThePostPendingWithABackoffLease() {
        Post post = pendingPost();
        post.setModerationAttempts(1);
        when(documentStore.findById("post-1", Post.class)).thenReturn(post);
        when(moderation.check("hello @bob"))
                .thenThrow(ModerationFailureException.transientFailure("TIMEOUT", "slow", null));

        worker.moderate("post-1").join();

        Map<String, Object> set = lastUpdateSection("$set");
        // Not approved, not yet flagged: the status field is untouched and the lease is pushed out.
        assertFalse(set.containsKey("moderationStatus"));
        assertNotNull(set.get("moderationLeaseUntil"));
        verifyNoInteractions(notifications);
    }

    @Test
    void exhaustedRetriesFlagThePostForAdministratorReview() {
        Post post = pendingPost();
        post.setModerationAttempts(5);
        when(documentStore.findById("post-1", Post.class)).thenReturn(post);
        when(moderation.check("hello @bob"))
                .thenThrow(ModerationFailureException.transientFailure("TIMEOUT", "slow", null));

        worker.moderate("post-1").join();

        assertEquals(ModerationStatus.FLAGGED, lastUpdateSection("$set").get("moderationStatus"));
    }

    @Test
    void alreadyDecidedPostIsNeverModeratedOrWrittenAgain() {
        Post decided = pendingPost();
        decided.setModerationStatus(ModerationStatus.APPROVED);
        when(documentStore.findById("post-1", Post.class)).thenReturn(decided);

        worker.moderate("post-1").join();

        verifyNoInteractions(moderation);
        verify(documentStore, never()).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
    }

    private ModerationStatus finalStatus() {
        return (ModerationStatus) lastUpdateSection("$set").get("moderationStatus");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastUpdateSection(String operator) {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(documentStore).updateFirst(any(Query.class), captor.capture(), eq(Post.class));
        Map<String, Object> section = new java.util.LinkedHashMap<>();
        boolean includeSet = "$set".equals(operator);
        boolean includeUnset = "$unset".equals(operator);
        for (Update.Operation operation : captor.getValue().operations()) {
            if (includeSet && operation.kind() == Update.Kind.SET) {
                section.put(operation.field(), operation.value());
            } else if (includeUnset && operation.kind() == Update.Kind.UNSET) {
                section.put(operation.field(), "");
            }
        }
        return section;
    }

    private static Post pendingPost() {
        Post post = new Post();
        post.setId("post-1");
        post.setUserId("1");
        post.setContent("hello @bob");
        post.setMentions(List.of("bob"));
        post.setModerationStatus(ModerationStatus.PENDING);
        post.setNotifyMentionsAfterModeration(true);
        return post;
    }
}
