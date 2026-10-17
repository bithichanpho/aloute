package com.aloute.service;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.repository.IUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/** Performs remote moderation off request threads and bounds retries for every failure path. */
@Service
public class PostModerationWorker {
    private static final Logger log = LoggerFactory.getLogger(PostModerationWorker.class);

    private final MongoTemplate mongo;
    private final ModerationService moderation;
    private final ModerationRetryPolicy retryPolicy;
    private final CacheManager cacheManager;
    private final IUserRepository users;
    private final ContentAccessService access;
    private final NotificationService notifications;
    private final MediaAssetLifecycleService postMediaAssets;

    @Autowired
    public PostModerationWorker(MongoTemplate mongo, ModerationService moderation,
            ModerationRetryPolicy retryPolicy, CacheManager cacheManager,
            IUserRepository users, ContentAccessService access, NotificationService notifications,
            MediaAssetLifecycleService postMediaAssets) {
        this.mongo = mongo;
        this.moderation = moderation;
        this.retryPolicy = retryPolicy;
        this.cacheManager = cacheManager;
        this.users = users;
        this.access = access;
        this.notifications = notifications;
        this.postMediaAssets = postMediaAssets;
    }

    /** Compatibility constructor for isolated service tests. */
    public PostModerationWorker(MongoTemplate mongo, ModerationService moderation,
            ModerationRetryPolicy retryPolicy, CacheManager cacheManager,
            IUserRepository users, ContentAccessService access, NotificationService notifications) {
        this(mongo, moderation, retryPolicy, cacheManager, users, access, notifications, null);
    }

    @Async("moderationExecutor")
    public CompletableFuture<Void> moderate(String postId) {
        Post snapshot = null;
        try {
            snapshot = mongo.findById(postId, Post.class);
            if (snapshot == null || snapshot.getModerationStatus() != ModerationStatus.PENDING) {
                return CompletableFuture.completedFuture(null);
            }

            String content = snapshot.getContent();
            boolean hasText = content != null && !content.isBlank();
            boolean hasImage = snapshot.getImageUrl() != null && !snapshot.getImageUrl().isBlank();
            boolean hasVideo = snapshot.getVideoUrl() != null && !snapshot.getVideoUrl().isBlank();
            if (!hasText && !hasImage && !hasVideo) {
                throw ModerationFailureException.permanentFailure(
                        "EMPTY_POST", "Pending post contains no content to moderate", null);
            }

            // Media-only posts are valid; an empty text payload is never sent to the moderator.
            ModerationResult text = hasText
                    ? moderation.check(content)
                    : new ModerationResult("safe", 0.0);
            ModerationStatus finalStatus = moderation.resolveStatus(text);
            if (!moderation.isMediaApproved(snapshot.getImageUrl(), "image")
                    || !moderation.isMediaApproved(snapshot.getVideoUrl(), "video")) {
                finalStatus = ModerationStatus.HIDDEN;
            }

            Query unchanged = unchangedPostQuery(snapshot);
            LocalDateTime completedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            Update result = new Update()
                    .set("moderationStatus", finalStatus)
                    .set("toxicityScore", text.getScore())
                    .set("moderationLastAttemptAt", completedAt)
                    .unset("moderationLeaseUntil")
                    .unset("moderationFailureReason")
                    .unset("notifyMentionsAfterModeration");
            if (mongo.updateFirst(unchanged, result, Post.class).getModifiedCount() > 0) {
                snapshot.setModerationStatus(finalStatus);
                snapshot.setToxicityScore(text.getScore());
                if (finalStatus == ModerationStatus.APPROVED && snapshot.isNotifyMentionsAfterModeration()) {
                    notifyReadableMentions(snapshot);
                }
                if (postMediaAssets != null) {
                    try {
                        if (finalStatus == ModerationStatus.HIDDEN) {
                            postMediaAssets.deletePostMedia(snapshot);
                        }
                    } catch (RuntimeException lifecycleFailure) {
                        log.warn("Post-media lifecycle update deferred (postId={}, cause={})",
                                postId, lifecycleFailure.getClass().getSimpleName());
                    }
                }
                evictFeedCache();
            }
        } catch (RuntimeException failure) {
            handleFailure(postId, snapshot, failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    private Query unchangedPostQuery(Post snapshot) {
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("_id").is(snapshot.getId()),
                Criteria.where("moderationStatus").is(ModerationStatus.PENDING),
                Criteria.where("content").is(snapshot.getContent()),
                Criteria.where("imageUrl").is(snapshot.getImageUrl()),
                Criteria.where("videoUrl").is(snapshot.getVideoUrl()));
        if (snapshot.getModerationLastAttemptAt() != null) {
            criteria = new Criteria().andOperator(criteria,
                    Criteria.where("moderationLastAttemptAt").is(snapshot.getModerationLastAttemptAt()));
        }
        return Query.query(criteria);
    }

    private void handleFailure(String postId, Post snapshot, RuntimeException failure) {
        Post current = snapshot;
        if (current == null) current = mongo.findById(postId, Post.class);
        if (current == null || current.getModerationStatus() != ModerationStatus.PENDING) return;

        boolean retryable = retryPolicy.isRetryable(failure);
        boolean exhausted = retryPolicy.exhausted(current.getModerationAttempts());
        boolean requireReview = !retryable || exhausted;
        String failureCode = retryPolicy.failureCode(failure);
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("moderationFailureReason", failureCode);
        if (requireReview) {
            update.set("moderationStatus", ModerationStatus.FLAGGED).unset("moderationLeaseUntil");
        } else {
            update.set("moderationLeaseUntil",
                    now.plusSeconds(retryPolicy.delaySeconds(current.getModerationAttempts())));
        }
        mongo.updateFirst(unchangedPostQuery(current), update, Post.class);
        log.warn("Moderation failed for post {}; retryable={}, attempts={}/{}, reviewRequired={}, code={}",
                postId, retryable, current.getModerationAttempts(), retryPolicy.maxAttempts(),
                requireReview, failureCode);
        log.debug("Post moderation failure details for post {}", postId, failure);
        if (requireReview) evictFeedCache();
    }

    private void notifyReadableMentions(Post post) {
        Long authorId;
        try { authorId = Long.valueOf(post.getUserId()); }
        catch (RuntimeException invalidAuthor) { return; }
        if (post.getMentions() == null) return;
        for (String mention : post.getMentions()) {
            users.findByUsernameIgnoreCase(mention)
                    .filter(user -> access.canRead(post, user.getId()))
                    .ifPresent(user -> notifications.notify(user.getId(), authorId, "MENTION",
                            post.getId(), "đã nhắc đến bạn trong bài viết"));
        }
    }

    private void evictFeedCache() {
        var feedCache = cacheManager.getCache("feedPages");
        if (feedCache != null) feedCache.clear();
    }
}
