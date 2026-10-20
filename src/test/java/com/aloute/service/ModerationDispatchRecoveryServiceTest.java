package com.aloute.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aloute.model.document.Post;
import com.aloute.model.document.Comment;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

class ModerationDispatchRecoveryServiceTest {
    @Test
    void identifiesDirectAndWrappedExecutorRejectionsOnly() {
        ModerationDispatchRecoveryService recovery =
                new ModerationDispatchRecoveryService(mock(MongoTemplate.class));

        assertTrue(recovery.isExecutorRejection(new RejectedExecutionException("full")));
        assertTrue(recovery.isExecutorRejection(
                new CompletionException(new RejectedExecutionException("full"))));
        assertFalse(recovery.isExecutorRejection(new IllegalStateException("moderation failed")));
    }

    @Test
    void rejectedTaskRollsBackClaimInsteadOfConsumingRetryBudget() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Post.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        ModerationDispatchRecoveryService recovery = new ModerationDispatchRecoveryService(mongo);
        LocalDateTime claimedAt = LocalDateTime.now().withNano(0);

        recovery.rollbackRejectedPost("post-1", claimedAt, new IllegalStateException("queue full"));

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
    }

    @Test
    void rejectedCommentTaskAlsoRollsBackItsClaim() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Comment.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
        ModerationDispatchRecoveryService recovery = new ModerationDispatchRecoveryService(mongo);
        LocalDateTime claimedAt = LocalDateTime.now().withNano(0);

        recovery.rollbackRejectedComment("comment-1", claimedAt,
                new RejectedExecutionException("queue full"));

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Comment.class));
    }
}
