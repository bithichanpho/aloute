package com.aloute.service;

import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

/** Runs remote moderation outside PostgreSQL transactions; only the atomic state/counter writes are transactional. */
@Service
public class CommentModerationWorker {
    private static final Logger log = LoggerFactory.getLogger(CommentModerationWorker.class);
    private final PostgresDocumentStore documentStore;
    private final ModerationService moderation;
    private final CacheManager cacheManager;
    private final ModerationRetryPolicy retryPolicy;
    private final NotificationService notifications;
    private final TransactionTemplate databaseTransaction;

    public CommentModerationWorker(
            PostgresDocumentStore documentStore, ModerationService moderation, ModerationRetryPolicy retryPolicy,
            NotificationService notifications, CacheManager cacheManager,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.documentStore = documentStore;
        this.moderation = moderation;
        this.cacheManager = cacheManager;
        this.retryPolicy = retryPolicy;
        this.notifications = notifications;
        this.databaseTransaction = new TransactionTemplate(transactionManager);
    }

    @Async("moderationExecutor")
    public CompletableFuture<Void> moderate(String commentId) {
        Comment snapshot = null;
        try {
            snapshot = documentStore.findById(commentId, Comment.class);
            if (snapshot == null || snapshot.getModerationStatus() != ModerationStatus.PENDING) {
                return CompletableFuture.completedFuture(null);
            }

            // IMPORTANT: the external HTTP request (up to MODERATION_TEXT_TIMEOUT) runs before
            // opening a PostgreSQL transaction, so slow upstream latency cannot hold a DB session.
            ModerationResult result = moderation.check(snapshot.getContent());
            ModerationStatus finalStatus = moderation.resolveStatus(result);
            Comment reviewed = snapshot;
            databaseTransaction.executeWithoutResult(tx -> persistDecision(reviewed, finalStatus));
        } catch (RuntimeException failure) {
            handleFailure(commentId, snapshot, failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void persistDecision(Comment snapshot, ModerationStatus finalStatus) {
        Query unchanged = unchangedCommentQuery(snapshot);
        boolean applyCounters = finalStatus == ModerationStatus.APPROVED
                && !snapshot.isModerationCountersApplied();
        Update update = new Update()
                .set("moderationStatus", finalStatus)
                .set("moderationLastAttemptAt", LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS))
                .unset("moderationLeaseUntil")
                .unset("moderationFailureReason");
        if (finalStatus == ModerationStatus.APPROVED) {
            update.set("moderationCountersApplied", true).unset("notifyAfterModeration");
        }

        if (documentStore.updateFirst(unchanged, update, Comment.class).getModifiedCount() == 0) return;
        if (applyCounters) {
            applyVisibleCounters(snapshot);
            TransactionCommitHooks.afterCommit(this::evictFeedCache);
        }
        if (finalStatus == ModerationStatus.APPROVED && snapshot.isNotifyAfterModeration()) {
            TransactionCommitHooks.afterCommit(() -> {
                try {
                    notifyAfterApproval(snapshot);
                } catch (RuntimeException notificationFailure) {
                    log.warn("Approved comment notification failed (commentId={}, cause={})",
                            snapshot.getId(), notificationFailure.getClass().getSimpleName());
                }
            });
        }
    }

    private Query unchangedCommentQuery(Comment snapshot) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("_id").is(snapshot.getId()),
                Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                Criteria.where("content").is(snapshot.getContent()));
        if (snapshot.getModerationLastAttemptAt() != null) {
            criteria = new Criteria().andOperator(criteria,
                    Criteria.where("moderationLastAttemptAt").is(snapshot.getModerationLastAttemptAt()));
        }
        return Query.query(criteria);
    }

    private void handleFailure(String commentId, Comment snapshot, RuntimeException failure) {
        Comment current = snapshot;
        if (current == null) current = documentStore.findById(commentId, Comment.class);
        if (current == null || current.getModerationStatus() != ModerationStatus.PENDING) return;

        boolean retryable = retryPolicy.isRetryable(failure);
        boolean exhausted = retryPolicy.exhausted(current.getModerationAttempts());
        boolean requireReview = !retryable || exhausted;
        String failureCode = retryPolicy.failureCode(failure);
        Update update = new Update().set("moderationFailureReason", failureCode);
        if (requireReview) {
            update.set("moderationStatus", ModerationStatus.FLAGGED).unset("moderationLeaseUntil");
        } else {
            update.set("moderationLeaseUntil",
                    LocalDateTime.now().plusSeconds(retryPolicy.delaySeconds(current.getModerationAttempts())));
        }
        documentStore.updateFirst(unchangedCommentQuery(current), update, Comment.class);
        log.warn("Moderation failed for comment {}; retryable={}, attempts={}/{}, reviewRequired={}, code={}",
                commentId, retryable, current.getModerationAttempts(), retryPolicy.maxAttempts(),
                requireReview, failureCode);
        log.debug("Comment moderation failure details for comment {}", commentId, failure);
    }

    private void applyVisibleCounters(Comment comment) {
        documentStore.updateFirst(Query.query(Criteria.where("_id").is(comment.getPostId())),
                new Update().inc("commentCount", 1), Post.class);
        if (comment.getParentCommentId() != null && !comment.getParentCommentId().isBlank()) {
            documentStore.updateFirst(Query.query(Criteria.where("_id").is(comment.getParentCommentId())),
                    new Update().inc("replyCount", 1), Comment.class);
        }
    }

    private void evictFeedCache() {
        try {
            var cache = cacheManager.getCache("feedPages");
            if (cache != null) cache.clear();
        } catch (RuntimeException cacheFailure) {
            log.warn("Feed cache eviction failed after comment moderation; TTL will expire it ({})",
                    cacheFailure.getClass().getSimpleName());
        }
    }

    private void notifyAfterApproval(Comment comment) {
        final Long senderId;
        try {
            senderId = Long.valueOf(comment.getUserId());
        } catch (RuntimeException invalidUserId) {
            return;
        }
        if (comment.getParentCommentId() != null && !comment.getParentCommentId().isBlank()) {
            Comment parent = documentStore.findById(comment.getParentCommentId(), Comment.class);
            if (parent == null || parent.getModerationStatus() != ModerationStatus.APPROVED) return;
            try {
                Long receiverId = Long.valueOf(parent.getUserId());
                if (!receiverId.equals(senderId)) {
                    notifications.notify(receiverId, senderId, "COMMENT", comment.getPostId(),
                            "đã trả lời bình luận của bạn");
                }
            } catch (RuntimeException invalidParentUser) {
                log.debug("Skipping reply notification for comment {} due to invalid parent owner", comment.getId());
            }
            return;
        }
        Post post = documentStore.findById(comment.getPostId(), Post.class);
        if (post == null) return;
        try {
            notifications.notify(Long.valueOf(post.getUserId()), senderId, "COMMENT", post.getId(),
                    "đã bình luận về bài viết của bạn");
        } catch (RuntimeException invalidPostOwner) {
            log.debug("Skipping comment notification for comment {} due to invalid post owner", comment.getId());
        }
    }
}
