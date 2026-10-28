package com.aloute.service;

import com.aloute.notifications.api.Notifications;

import com.aloute.common.util.ReactionType;
import com.aloute.dto.response.LikeResponse;
import com.aloute.dto.response.ReactionResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.repository.ILikeRepository;
import com.aloute.persistence.postgres.document.DeleteResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.stereotype.Service;
import com.aloute.common.util.TransactionCommitHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    private final ILikeRepository likeRepository;
    private final PostService postService;
    private final Notifications notificationService;
    private final PostgresDocumentStore documentStore;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private final PostgresTransactionRetryExecutor transactionRetryExecutor;

    @Autowired
    public LikeService(
            ILikeRepository likeRepository,
            PostService postService,
            Notifications notificationService,
            PostgresDocumentStore documentStore,
            FeedCacheInvalidator feedCacheInvalidator,
            PostgresTransactionRetryExecutor transactionRetryExecutor) {
        this.likeRepository = likeRepository;
        this.postService = postService;
        this.notificationService = notificationService;
        this.documentStore = documentStore;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.transactionRetryExecutor = transactionRetryExecutor;
    }

    /** Compatibility constructor retained for tests and callers without cache configuration. */
    public LikeService(
            ILikeRepository likeRepository,
            PostService postService,
            Notifications notificationService,
            PostgresDocumentStore documentStore,
            FeedCacheInvalidator feedCacheInvalidator) {
        this(likeRepository, postService, notificationService, documentStore, feedCacheInvalidator, null);
    }

    /** Compatibility constructor used by focused unit tests. */
    public LikeService(
            ILikeRepository likeRepository,
            PostService postService,
            Notifications notificationService,
            PostgresDocumentStore documentStore) {
        this(likeRepository, postService, notificationService, documentStore, null, null);
    }

    public LikeResponse setLike(String postId, Long currentUserId, boolean desiredLiked) {
        if (!desiredLiked) {
            ReactionResponse reaction = setReaction(postId, currentUserId, null);
            return new LikeResponse(postId, false, reaction.getTotalCount());
        }
        ReactionResponse reaction;
        try {
            // Unlike a toggle endpoint, this endpoint expresses desired state. Repeating liked=true
            // must remain a like instead of turning it off on a network retry or double-click.
            reaction = executeTransaction(
                    () -> setReactionInTransaction(postId, currentUserId, ReactionType.LIKE));
        } catch (DuplicateKeyException concurrentInsert) {
            reaction = readCommittedReaction(postId, currentUserId, concurrentInsert);
        }
        return new LikeResponse(postId, reaction.getCurrentUserReaction() != null, reaction.getTotalCount());
    }

    public boolean toggleLike(String postId, Long currentUserId) {
        String userIdStr = String.valueOf(currentUserId);
        try {
            return executeTransaction(() -> {
                boolean currentlyLiked = likeRepository.existsByPostIdAndUserId(postId, userIdStr);
                ReactionResponse result = setReactionInTransaction(
                        postId, currentUserId, currentlyLiked ? null : ReactionType.LIKE);
                return result.getCurrentUserReaction() != null;
            });
        } catch (DuplicateKeyException concurrentLike) {
            // Simultaneous requests that both try to create the same like resolve to the persisted row.
            postService.requireReadablePost(postId, currentUserId);
            return likeRepository.existsByPostIdAndUserId(postId, userIdStr);
        }
    }

    public ReactionResponse setReaction(String postId, Long currentUserId, String requestedType) {
        try {
            return executeTransaction(
                    () -> setReactionInTransaction(postId, currentUserId, requestedType));
        } catch (DuplicateKeyException concurrentInsert) {
            // A concurrent request won the unique (postId,userId) insert. Return committed state.
            return readCommittedReaction(postId, currentUserId, concurrentInsert);
        }
    }

    private ReactionResponse setReactionInTransaction(
            String postId, Long currentUserId, String requestedType) {
        Post post = postService.requireReadablePost(postId, currentUserId);
        String userIdStr = String.valueOf(currentUserId);
        boolean wantsReaction = requestedType != null && !requestedType.isBlank();
        String normalizedType = wantsReaction ? ReactionType.normalize(requestedType) : null;

        Like existing = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);
        String previousReactionType = existing == null ? null : existing.getReactionType();

        if (!wantsReaction) {
            if (existing != null) removeReaction(postId, existing);
        } else if (existing == null) {
            // A duplicate-key race aborts this attempt and is reconciled after transaction rollback.
            likeRepository.save(new Like(postId, userIdStr, normalizedType));
            postService.adjustReactionCount(postId, normalizedType, 1);
            notifyReacted(post, currentUserId);
        } else if (!normalizedType.equals(existing.getReactionType())) {
            String oldType = existing.getReactionType();
            existing.setReactionType(normalizedType);
            likeRepository.save(existing);
            postService.adjustReactionCount(postId, oldType, -1);
            postService.adjustReactionCount(postId, normalizedType, 1);
        }

        Post refreshed = postService.getPostOrThrow(postId);
        Like current = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);
        Map<String, Integer> counts = refreshed.getReactionCounts() != null
                ? refreshed.getReactionCounts() : new HashMap<>();
        String resultingReactionType = current != null ? current.getReactionType() : null;
        if (!Objects.equals(previousReactionType, resultingReactionType) && feedCacheInvalidator != null) {
            TransactionCommitHooks.afterCommit(() -> feedCacheInvalidator.invalidateViewer(currentUserId));
        }
        return new ReactionResponse(postId, resultingReactionType,
                Math.max(0, refreshed.getLikeCount()), counts);
    }

    private ReactionResponse readCommittedReaction(
            String postId, Long currentUserId, DuplicateKeyException originalFailure) {
        postService.requireReadablePost(postId, currentUserId);
        Like existing = likeRepository
                .findByPostIdAndUserId(postId, String.valueOf(currentUserId))
                .orElseThrow(() -> originalFailure);
        Post refreshed = postService.getPostOrThrow(postId);
        Map<String, Integer> counts = refreshed.getReactionCounts() == null
                ? new HashMap<>() : refreshed.getReactionCounts();
        return new ReactionResponse(postId, existing.getReactionType(),
                Math.max(0, refreshed.getLikeCount()), counts);
    }

    private <T> T executeTransaction(Supplier<T> operation) {
        return transactionRetryExecutor == null ? operation.get() : transactionRetryExecutor.execute(operation);
    }

    private void removeReaction(String postId, Like existing) {
        Query query = Query.query(
                Criteria.where("postId").is(postId).and("userId").is(existing.getUserId()));
        DeleteResult result = documentStore.remove(query, Like.class);
        if (result.getDeletedCount() > 0) {
            postService.adjustReactionCount(postId, existing.getReactionType(), -1);
        }
    }

    private void notifyReacted(Post post, Long reactorId) {
        Runnable notification = () -> {
            try {
                Long ownerId = Long.valueOf(post.getUserId());
                notificationService.notify(ownerId, reactorId, "LIKE", post.getId(), "đã thích bài viết của bạn");
            } catch (RuntimeException notificationFailure) {
                // This runs after PostgreSQL commits. Never let notification failure make the caller
                // retry an already-committed reaction transaction.
                log.warn("Like notification enqueue failed (cause={})",
                        notificationFailure.getClass().getSimpleName());
            }
        };
        // Use the shared hook so outbox writes know they are running after the original
        // transaction has committed and can open a fresh REQUIRES_NEW transaction.
        TransactionCommitHooks.afterCommit(notification);
    }
}
