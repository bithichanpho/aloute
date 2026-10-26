package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.dto.request.CreatePostRequest;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostVisibility;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.persistence.postgres.document.UpdateResult;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;

/**
 * Editing text or media must send the post back through moderation. Otherwise an author could
 * publish harmless content, then edit it into violating content that stays APPROVED, or edit a
 * FLAGGED post to clear its flag.
 */
class PostEditModerationTest {
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final PostService posts = mock(PostService.class);
    private final ModerationService moderation = mock(ModerationService.class);
    private final PostModerationCoordinator coordinator = mock(PostModerationCoordinator.class);

    private final SocialFeatureService service =
            new SocialFeatureService(
                    documentStore,
                    access,
                    mock(IUserRepository.class),
                    mock(IFriendRepository.class),
                    mock(ICloseFriendRepository.class),
                    posts,
                    moderation,
                    mock(AuthRateLimitService.class),
                    mock(NotificationService.class),
                    coordinator,
                    null,
                    null,
                    null);

    @BeforeEach
    void updateSucceeds() {
        when(documentStore.updateFirst(any(Query.class), any(Update.class), eq(Post.class)))
                .thenReturn(new UpdateResult(1L, 1L));
    }

    @Test
    void editingContentSendsApprovedPostBackToPendingAndQueuesIt() {
        when(moderation.isEnabled()).thenReturn(true);
        stubOwnPost(ModerationStatus.APPROVED);

        service.edit(1L, "post-1", edit("now different text", PostVisibility.PUBLIC));

        Map<String, Object> set = updateSection("$set");
        assertEquals(ModerationStatus.PENDING, set.get("moderationStatus"));
        assertEquals(0, set.get("moderationAttempts"));
        assertEquals(0.0, set.get("toxicityScore"));
        assertEquals(true, set.get("notifyMentionsAfterModeration"));
        assertTrue(updateSection("$unset").containsKey("moderationLeaseUntil"));
        verify(coordinator).submit("post-1");
        // The post left every viewer's feed, not only the author's.
        verify(posts).invalidateAllFeedCache();
    }

    @Test
    void editingContentCannotClearAFlaggedPost() {
        when(moderation.isEnabled()).thenReturn(true);
        stubOwnPost(ModerationStatus.FLAGGED);

        service.edit(1L, "post-1", edit("rewritten", PostVisibility.PUBLIC));

        // Re-review, never a direct jump from FLAGGED to APPROVED.
        assertEquals(ModerationStatus.PENDING, updateSection("$set").get("moderationStatus"));
        verify(coordinator).submit("post-1");
    }

    @Test
    void editingMediaAloneAlsoRequiresReview() {
        when(moderation.isEnabled()).thenReturn(true);
        stubOwnPost(ModerationStatus.APPROVED);
        CreatePostRequest request = edit("original text", PostVisibility.PUBLIC);
        request.setImageUrl("https://res.cloudinary.com/demo/image/upload/new.png");

        service.edit(1L, "post-1", request);

        assertEquals(ModerationStatus.PENDING, updateSection("$set").get("moderationStatus"));
        verify(coordinator).submit("post-1");
    }

    @Test
    void visibilityOnlyEditKeepsTheExistingModerationDecision() {
        when(moderation.isEnabled()).thenReturn(true);
        stubOwnPost(ModerationStatus.FLAGGED);

        service.edit(1L, "post-1", edit("original text", PostVisibility.ONLY_ME));

        // Neither re-approves nor un-flags, and does not waste a moderation call.
        assertFalse(updateSection("$set").containsKey("moderationStatus"));
        assertEquals(PostVisibility.ONLY_ME, updateSection("$set").get("visibility"));
        verify(coordinator, never()).submit(anyString());
    }

    @Test
    void disabledModerationKeepsDemoModeApprovedWithoutQueueing() {
        when(moderation.isEnabled()).thenReturn(false);
        stubOwnPost(ModerationStatus.APPROVED);

        service.edit(1L, "post-1", edit("now different text", PostVisibility.PUBLIC));

        assertEquals(ModerationStatus.APPROVED, updateSection("$set").get("moderationStatus"));
        verify(coordinator, never()).submit(anyString());
    }

    @Test
    void queueFailureDoesNotFailTheEditAndPostStaysPending() {
        when(moderation.isEnabled()).thenReturn(true);
        stubOwnPost(ModerationStatus.APPROVED);
        doThrow(new IllegalStateException("queue down")).when(coordinator).submit("post-1");

        assertDoesNotThrow(
                () -> service.edit(1L, "post-1", edit("now different text", PostVisibility.PUBLIC)));

        assertEquals(ModerationStatus.PENDING, updateSection("$set").get("moderationStatus"));
    }

    private void stubOwnPost(ModerationStatus status) {
        Post post = new Post();
        post.setId("post-1");
        post.setUserId("1");
        post.setContent("original text");
        post.setVisibility(PostVisibility.PUBLIC);
        post.setModerationStatus(status);
        post.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        when(access.requirePost("post-1", 1L)).thenReturn(post);
    }

    private static CreatePostRequest edit(String content, PostVisibility visibility) {
        CreatePostRequest request = new CreatePostRequest();
        request.setContent(content);
        request.setVisibility(visibility);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateSection(String operator) {
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
}
