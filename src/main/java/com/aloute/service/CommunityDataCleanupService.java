package com.aloute.service;

import com.aloute.model.document.Comment;
import com.aloute.model.document.CommentReaction;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.Repost;
import com.aloute.model.document.SavedPost;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import org.springframework.stereotype.Service;

/** Idempotently removes all content owned by a deleted community. */
@Service
public class CommunityDataCleanupService {
    private static final int POST_BATCH_SIZE = 100;
    private static final int COMMENT_BATCH_SIZE = 500;
    private static final List<String> COMMUNITY_NOTIFICATION_TYPES = List.of(
            "COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE");

    private final PostgresDocumentStore documentStore;
    private final PostgresTransactionRetryExecutor transactions;
    private final MediaAssetLifecycleService mediaAssets;
    private final INotificationRepository notifications;
    private final INotificationOutboxRepository notificationOutbox;
    private final FeedCacheInvalidator feedCache;

    public CommunityDataCleanupService(
            PostgresDocumentStore documentStore,
            PostgresTransactionRetryExecutor transactions,
            MediaAssetLifecycleService mediaAssets,
            INotificationRepository notifications,
            INotificationOutboxRepository notificationOutbox,
            FeedCacheInvalidator feedCache) {
        this.documentStore = documentStore;
        this.transactions = transactions;
        this.mediaAssets = mediaAssets;
        this.notifications = notifications;
        this.notificationOutbox = notificationOutbox;
        this.feedCache = feedCache;
    }

    /**
     * Bounded and retry-safe cleanup. A failure leaves the PostgreSQL cleanup job in place, so a
     * later pass resumes from the remaining PostgreSQL documents instead of relying on in-memory work.
     */
    public void deleteCommunityData(Long communityId, String avatarUrl, String coverUrl) {
        if (communityId == null) throw new IllegalArgumentException("communityId is required");
        String id = String.valueOf(communityId);

        deleteCommunityPostsInBatches(id);
        notifications.deleteByReferenceIdAndTypeIn(id, COMMUNITY_NOTIFICATION_TYPES);
        notificationOutbox.deleteByReferenceIdAndNotificationTypeIn(
                id, COMMUNITY_NOTIFICATION_TYPES);

        mediaAssets.deleteIfUnreferenced(avatarUrl);
        mediaAssets.deleteIfUnreferenced(coverUrl);
        feedCache.invalidateAll();
    }

    private void deleteCommunityPostsInBatches(String communityId) {
        while (true) {
            List<Post> posts = documentStore.find(
                    Query.query(Criteria.where("communityId").is(communityId))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(POST_BATCH_SIZE),
                    Post.class);
            if (posts.isEmpty()) return;

            List<String> postIds = idsOf(posts);
            if (postIds.isEmpty()) {
                documentStore.remove(Query.query(Criteria.where("communityId").is(communityId)), Post.class);
                continue;
            }

            // Older releases represented a repost as another Post document. Remove those wrappers
            // as well, otherwise they retain a dangling repostOfId after the original disappears.
            deleteLegacyRepostPosts(postIds);
            deletePostBatch(posts, postIds);
        }
    }

    private void deleteLegacyRepostPosts(Collection<String> originalIds) {
        while (true) {
            List<Post> wrappers = documentStore.find(
                    Query.query(Criteria.where("repostOfId").in(originalIds))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(POST_BATCH_SIZE),
                    Post.class);
            if (wrappers.isEmpty()) return;
            List<String> wrapperIds = idsOf(wrappers);
            if (wrapperIds.isEmpty()) {
                documentStore.remove(Query.query(Criteria.where("repostOfId").in(originalIds)), Post.class);
                continue;
            }
            deleteLegacyRepostPosts(wrapperIds);
            deletePostBatch(wrappers, wrapperIds);
        }
    }

    private void deletePostBatch(List<Post> posts, List<String> postIds) {
        // SQL notifications cannot participate in the PostgreSQL transaction. Removing them first is
        // safe and idempotent; if PostgreSQL then fails, the durable job retries the remaining cleanup.
        notifications.deleteByReferenceIdIn(postIds);
        notificationOutbox.deleteByReferenceIdIn(postIds);
        deleteCommentsInBatches(postIds);

        inDatabaseTransaction(() -> {
            Query byPost = Query.query(Criteria.where("postId").in(postIds));
            documentStore.remove(byPost, Like.class);
            documentStore.remove(byPost, Repost.class);
            documentStore.remove(byPost, SavedPost.class);
            documentStore.remove(byPost, PostRevision.class);
            documentStore.remove(Query.query(new Criteria().andOperator(
                    Criteria.where("targetType").is("POST"),
                    Criteria.where("targetId").in(postIds))), ContentReport.class);
            documentStore.remove(Query.query(Criteria.where("_id").in(postIds)), Post.class);

            // These durable tombstones are part of the same PostgreSQL transaction as post deletion.
            // Cloudinary I/O is performed later by MediaAssetLifecycleService's retry worker.
            posts.forEach(mediaAssets::queuePostMediaDeletion);
            return null;
        });
    }

    private void deleteCommentsInBatches(Collection<String> postIds) {
        while (true) {
            List<Comment> comments = documentStore.find(
                    Query.query(Criteria.where("postId").in(postIds))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(COMMENT_BATCH_SIZE),
                    Comment.class);
            if (comments.isEmpty()) return;
            List<String> commentIds = comments.stream()
                    .map(Comment::getId).filter(Objects::nonNull).toList();
            if (commentIds.isEmpty()) {
                documentStore.remove(Query.query(Criteria.where("postId").in(postIds)), Comment.class);
                continue;
            }
            inDatabaseTransaction(() -> {
                documentStore.remove(Query.query(Criteria.where("commentId").in(commentIds)),
                        CommentReaction.class);
                documentStore.remove(Query.query(new Criteria().andOperator(
                        Criteria.where("targetType").is("COMMENT"),
                        Criteria.where("targetId").in(commentIds))), ContentReport.class);
                documentStore.remove(Query.query(Criteria.where("_id").in(commentIds)), Comment.class);
                return null;
            });
        }
    }

    private List<String> idsOf(List<Post> posts) {
        return posts.stream().map(Post::getId).filter(Objects::nonNull).toList();
    }

    private <T> T inDatabaseTransaction(Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(work);
    }
}
