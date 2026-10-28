package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.*;
import com.aloute.identity.api.ActionRateLimit;
import com.aloute.media.api.PostMediaAttachments;
import com.aloute.model.document.*;
import com.aloute.moderation.api.ModerationPolicy;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.api.*;
import com.aloute.posts.application.PostEditingService;
import com.aloute.posts.domain.PostAccess;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class PostEditingServiceTest {
    private final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    private final PostAccess access = mock(PostAccess.class);
    private final ActionRateLimit limits = mock(ActionRateLimit.class);
    private final ModerationPolicy moderation = mock(ModerationPolicy.class);
    private final PostReviewDispatch review = mock(PostReviewDispatch.class);
    private final PostMediaAttachments media = mock(PostMediaAttachments.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PostEditingService service = new PostEditingService(store, access, limits, moderation, review, media, events);

    @Test
    void unauthenticatedInternalCallsCannotReachPersistence() {
        assertThrows(UnauthorizedException.class, () -> service.edit(null, "p", command("text", "PUBLIC")));
        assertThrows(UnauthorizedException.class, () -> service.history(null, "p"));
        verifyNoInteractions(access, store, limits, media, events, review);
    }

    @Test
    void nonAuthorCannotEditOrReadRevisionHistory() {
        Post post = ownPost();
        post.setUserId("2");
        assertThrows(ForbiddenException.class, () -> service.edit(1L, "p", command("text", "PUBLIC")));
        assertThrows(ForbiddenException.class, () -> service.history(1L, "p"));
        verifyNoInteractions(store, media, events, review);
    }

    @Test
    void noChangeDoesNotWriteARevisionOrDispatchAnything() {
        ownPost();
        service.edit(1L, "p", command("text", "PUBLIC"));
        verifyNoInteractions(store, media, events, review);
    }

    @Test
    void mediaOwnershipFailureAndConcurrentUpdateConflictCannotPublishChanges() {
        ownPost();
        doThrow(new BadRequestException("foreign upload")).when(media).validatePostMediaUrl(any(), any(), any());
        assertThrows(BadRequestException.class, () -> service.edit(1L, "p", command("new text", "PUBLIC")));
        verifyNoInteractions(store, events, review);
        reset(media);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(0, 0));
        assertThrows(BadRequestException.class, () -> service.edit(1L, "p", command("new text", "PUBLIC")));
        verify(media, never()).attachPostMedia(any(), any(), any());
        verifyNoInteractions(events, review);
    }

    @Test
    void privacyOnlyEditPreservesModerationAndEmitsGlobalCacheHintWithoutPostContent() {
        Post post = ownPost();
        post.setModerationStatus(ModerationStatus.FLAGGED);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));

        service.edit(1L, "p", command("text", "ONLY_ME"));

        var update = ArgumentCaptor.forClass(Update.class);
        verify(store).updateFirst(any(), update.capture(), eq(Post.class));
        assertTrue(update.getValue().operations().stream().noneMatch(op -> op.field().equals("moderationStatus")));
        var event = ArgumentCaptor.forClass(PostChanged.class);
        verify(events).publishEvent(event.capture());
        assertTrue(event.getValue().invalidateAll());
        assertEquals("p", event.getValue().aggregateId());
        assertEquals("POST_EDITED", event.getValue().type());
        assertEquals(1, event.getValue().version());
        assertNotNull(event.getValue().eventId());
        assertNotNull(event.getValue().occurredAt());
        assertFalse(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(event.getValue())
                .contains("text"));
        verifyNoInteractions(review);
    }

    @Test
    void eventAndReviewWaitForAnExistingTransactionToCommit() {
        ownPost();
        when(moderation.isEnabled()).thenReturn(true);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.edit(1L, "p", command("changed", "PUBLIC"));
            verifyNoInteractions(events, review);
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(events).publishEvent(any(PostChanged.class));
            verify(review).submit("p");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void rollbackDoesNotPublishCacheHintsOrDispatchReview() {
        ownPost();
        when(moderation.isEnabled()).thenReturn(true);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.edit(1L, "p", command("changed", "PUBLIC"));
            TransactionSynchronizationUtils.triggerAfterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(events, review);
    }

    @Test
    void historyIsImmutableNewestFirstAndBoundedToOneHundredRevisions() {
        Post post = ownPost();
        post.setEditHistory(IntStream.range(0, 110).mapToObj(i -> {
            PostRevision revision = new PostRevision();
            revision.setPostId("p");
            revision.setContent("revision " + i);
            revision.setCreatedAt(Instant.EPOCH.plusSeconds(i));
            return revision;
        }).toList());
        var history = service.history(1L, "p");
        assertEquals(100, history.size());
        assertEquals("revision 109", history.getFirst().content());
        assertEquals("revision 10", history.getLast().content());
        assertThrows(UnsupportedOperationException.class, () -> history.clear());
        verifyNoInteractions(store);
    }

    private Post ownPost() {
        Post post = new Post();
        post.setId("p");
        post.setUserId("1");
        post.setContent("text");
        post.setVisibility(PostVisibility.PUBLIC);
        post.setModerationStatus(ModerationStatus.APPROVED);
        when(access.requireReadable("p", 1L)).thenReturn(post);
        return post;
    }

    @Test
    void captionEditPreservesOriginalMediaAndStillRequiresReview() {
        Post post = ownPost(); post.setImageUrl("https://res.cloudinary.com/owned/original.jpg");
        when(moderation.isEnabled()).thenReturn(true);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        service.edit(1L, "p", new PostEditCommand("new caption", null, null, "CLOSE_FRIENDS", true, false, false));
        var update = ArgumentCaptor.forClass(Update.class);
        verify(store).updateFirst(any(), update.capture(), eq(Post.class));
        assertTrue(update.getValue().operations().stream().anyMatch(op ->
                op.field().equals("imageUrl") && post.getImageUrl().equals(op.value())));
        verify(media).validatePostMediaUrl(post.getImageUrl(), post.getImageUrl(), "1");
        verify(review).submit("p");
    }

    @Test
    void explicitNullStillRemovesMediaButCannotLeaveAnEmptyPost() {
        Post post = ownPost(); post.setImageUrl("https://res.cloudinary.com/owned/photo.jpg");
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        service.edit(1L, "p", new PostEditCommand("caption", null, null, "PUBLIC"));
        var update = ArgumentCaptor.forClass(Update.class);
        verify(store).updateFirst(any(), update.capture(), eq(Post.class));
        assertTrue(update.getValue().operations().stream().anyMatch(op -> op.field().equals("imageUrl") && op.value() == null));
        reset(store);
        assertThrows(BadRequestException.class, () -> service.edit(1L, "p", command(" ", "PUBLIC")));
        verifyNoInteractions(store);
    }

    private PostEditCommand command(String content, String visibility) {
        return new PostEditCommand(content, null, null, visibility);
    }
}
