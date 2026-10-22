package com.aloute.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aloute.model.document.Post;
import com.aloute.model.document.Comment;
import com.aloute.persistence.postgres.document.UpdateResult;
import org.junit.jupiter.api.Test;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

class ModerationDispatchRecoveryServiceTest {
    @Test
    void identifiesDirectAndWrappedExecutorRejectionsOnly() {
        ModerationDispatchRecoveryService recovery =
                new ModerationDispatchRecoveryService(mock(PostgresDocumentStore.class));

        assertTrue(recovery.isExecutorRejection(new RejectedExecutionException("full")));
        assertTrue(recovery.isExecutorRejection(
                new CompletionException(new RejectedExecutionException("full"))));
        assertFalse(recovery.isExecutorRejection(new IllegalStateException("moderation failed")));
    }

    @Test
    void rejectedTaskRollsBackClaimInsteadOfConsumingRetryBudget() {
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(documentStore.updateFirst(any(Query.class), any(Update.class), eq(Post.class)))
                .thenReturn(new UpdateResult(1L, 1L));
        ModerationDispatchRecoveryService recovery = new ModerationDispatchRecoveryService(documentStore);
        LocalDateTime claimedAt = LocalDateTime.now().withNano(0);

        recovery.rollbackRejectedPost("post-1", claimedAt, new IllegalStateException("queue full"));

        verify(documentStore).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
    }

    @Test
    void rejectedCommentTaskAlsoRollsBackItsClaim() {
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(documentStore.updateFirst(any(Query.class), any(Update.class), eq(Comment.class)))
                .thenReturn(new UpdateResult(1L, 1L));
        ModerationDispatchRecoveryService recovery = new ModerationDispatchRecoveryService(documentStore);
        LocalDateTime claimedAt = LocalDateTime.now().withNano(0);

        recovery.rollbackRejectedComment("comment-1", claimedAt,
                new RejectedExecutionException("queue full"));

        verify(documentStore).updateFirst(any(Query.class), any(Update.class), eq(Comment.class));
    }
}
