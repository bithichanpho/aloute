package com.aloute.service;

import com.aloute.model.document.Comment;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Restores a moderation lease when a task was never accepted by the bounded executor. */
@Service
public class ModerationDispatchRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(ModerationDispatchRecoveryService.class);
    private final MongoTemplate mongo;

    public ModerationDispatchRecoveryService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void rollbackRejectedPost(String postId, LocalDateTime claimedAt, RuntimeException failure) {
        rollbackClaim(postId, claimedAt, Post.class, "post", failure);
    }

    public void rollbackRejectedComment(String commentId, LocalDateTime claimedAt, RuntimeException failure) {
        rollbackClaim(commentId, claimedAt, Comment.class, "comment", failure);
    }

    /** Detect executor saturation whether Spring throws directly or completes the async future exceptionally. */
    public boolean isExecutorRejection(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.RejectedExecutionException
                    || "TaskRejectedException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return false;
    }

    private void rollbackClaim(String id, LocalDateTime claimedAt, Class<?> type,
            String label, RuntimeException failure) {
        if (id == null || claimedAt == null) return;
        var result = mongo.updateFirst(
                Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(id),
                        Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                        Criteria.where("moderationLastAttemptAt").is(claimedAt))),
                new Update()
                        // The remote moderation attempt never started: queue rejection is not a
                        // moderation failure and must not consume the user's retry budget.
                        .inc("moderationAttempts", -1)
                        .unset("moderationLeaseUntil")
                        .unset("moderationLastAttemptAt")
                        .unset("moderationFailureReason"),
                type);
        if (result.getModifiedCount() > 0) {
            log.warn("Moderation dispatch for {} {} was rejected; attempt restored for scanner retry ({})",
                    label, id, failure.getClass().getSimpleName());
        } else {
            log.debug("Moderation dispatch rollback skipped for {} {} because its lease has changed", label, id);
        }
    }
}
