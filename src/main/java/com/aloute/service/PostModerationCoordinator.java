package com.aloute.service;

import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Mongo-backed, lease-based queue with a bounded retry policy. */
@Service
public class PostModerationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PostModerationCoordinator.class);
    private final MongoTemplate mongo;
    private final PostModerationWorker worker;
    private final ModerationService moderation;
    private final ModerationRetryPolicy retryPolicy;
    private final CacheManager cacheManager;
    private final ModerationDispatchRecoveryService dispatchRecovery;
    private final long leaseSeconds;
    private final int scanLimit;

    public PostModerationCoordinator(MongoTemplate mongo, PostModerationWorker worker,
            ModerationService moderation, ModerationRetryPolicy retryPolicy, CacheManager cacheManager,
            ModerationDispatchRecoveryService dispatchRecovery,
            @Value("${app.moderation.lease-seconds:300}") long leaseSeconds,
            @Value("${app.moderation.scan-limit:100}") int requestedScanLimit,
            @Value("${app.moderation.executor.max-size:16}") int configuredMaxWorkers,
            @Value("${app.moderation.executor.queue-capacity:100}") int configuredQueueCapacity) {
        this.mongo = mongo;
        this.worker = worker;
        this.moderation = moderation;
        this.retryPolicy = retryPolicy;
        this.cacheManager = cacheManager;
        this.dispatchRecovery = dispatchRecovery;
        this.leaseSeconds = Math.max(30, Math.min(900, leaseSeconds));
        int safeMaxWorkers = Math.max(2, Math.min(64, configuredMaxWorkers));
        int safeQueueCapacity = Math.max(10, Math.min(2000, configuredQueueCapacity));
        int executorCapacity = safeMaxWorkers + safeQueueCapacity;
        this.scanLimit = Math.max(1, Math.min(Math.min(1000, requestedScanLimit), executorCapacity));
    }

    public void submit(String postId) {
        if (!moderation.isEnabled() || postId == null || postId.isBlank()) return;
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Criteria retryAvailable = new Criteria().orOperator(
                Criteria.where("moderationAttempts").is(null),
                Criteria.where("moderationAttempts").lt(retryPolicy.maxAttempts()));
        Query claim = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(postId),
                Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                retryAvailable,
                leaseExpired(now)));
        Update lease = new Update()
                .set("moderationLeaseUntil", now.plusSeconds(leaseSeconds))
                .set("moderationLastAttemptAt", now)
                .inc("moderationAttempts", 1);
        if (mongo.updateFirst(claim, lease, Post.class).getModifiedCount() != 1) return;
        try {
            worker.moderate(postId).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    if (dispatchRecovery.isExecutorRejection(failure)) {
                        RuntimeException rejection = failure instanceof RuntimeException runtimeFailure
                                ? runtimeFailure : new IllegalStateException("Moderation executor rejected task", failure);
                        handleDispatchFailure(postId, now, rejection);
                    }
                    log.warn("Moderation task for post {} failed ({})",
                            postId, failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException rejected) {
            handleDispatchFailure(postId, now, rejected);
        }
    }

    @Scheduled(fixedDelayString = "${app.moderation.scan-interval:30000}", initialDelayString = "${app.moderation.scan-initial-delay:10000}")
    public void recoverPendingPosts() {
        if (!moderation.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                        leaseExpired(now)))
                .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
                .limit(scanLimit);
        for (Post post : mongo.find(query, Post.class)) {
            if (retryPolicy.exhausted(post.getModerationAttempts())) {
                flagForReview(post, "MAX_RETRIES_EXCEEDED");
            } else {
                submit(post.getId());
            }
        }
    }

    private Criteria leaseExpired(LocalDateTime now) {
        return new Criteria().orOperator(
                Criteria.where("moderationLeaseUntil").is(null),
                Criteria.where("moderationLeaseUntil").lt(now));
    }

    private void handleDispatchFailure(String postId, LocalDateTime claimedAt, RuntimeException failure) {
        dispatchRecovery.rollbackRejectedPost(postId, claimedAt, failure);
    }

    private void flagForReview(Post post, String reason) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var result = mongo.updateFirst(Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(post.getId()),
                        Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                        Criteria.where("moderationAttempts").gte(retryPolicy.maxAttempts()),
                        leaseExpired(now))),
                new Update().set("moderationStatus", ModerationStatus.FLAGGED)
                        .set("moderationFailureReason", reason)
                        .unset("moderationLeaseUntil"), Post.class);
        if (result.getModifiedCount() > 0) {
            log.warn("Post {} exhausted moderation retries and was flagged for administrator review", post.getId());
            evictFeedCache();
        }
    }

    private void evictFeedCache() {
        try {
            var feedCache = cacheManager.getCache("feedPages");
            if (feedCache != null) feedCache.clear();
        } catch (RuntimeException cacheFailure) {
            log.warn("Feed cache eviction failed after moderation recovery; TTL will expire it ({})",
                    cacheFailure.getClass().getSimpleName());
        }
    }
}
