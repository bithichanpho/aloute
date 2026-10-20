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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Idempotently removes all content owned by a deleted community. */
@Service
public class CommunityDataCleanupService {
    private static final int POST_BATCH_SIZE = 100;
    private static final int COMMENT_BATCH_SIZE = 500;
    private static final List<String> COMMUNITY_NOTIFICATION_TYPES = List.of(
            "COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE");

    private final MongoTemplate mongo;
    private final MongoTransactionRetryExecutor transactions;
    private final MediaAssetLifecycleService mediaAssets;
    private final INotificationRepository notifications;
    private final INotificationOutboxRepository notificationOutbox;
    private final FeedCacheInvalidator feedCache;

    public CommunityDataCleanupService(
            MongoTemplate mongo,
            MongoTransactionRetryExecutor transactions,
            MediaAssetLifecycleService mediaAssets,
            INotificationRepository notifications,
            INotificationOutboxRepository notificationOutbox,
            FeedCacheInvalidator feedCache) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.mediaAssets = mediaAssets;
        this.notifications = notifications;
        this.notificationOutbox = notificationOutbox;
        this.feedCache = feedCache;
    }

    /**
     * Bounded and retry-safe cleanup. A failure leaves the PostgreSQL cleanup job in place, so a
     * later pass resumes from the remaining Mongo documents instead of relying on in-memory work.
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
            List<Post> posts = mongo.find(
                    Query.query(Criteria.where("communityId").is(communityId))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(POST_BATCH_SIZE),
                    Post.class);
            if (posts.isEmpty()) return;

            List<String> postIds = idsOf(posts);
            if (postIds.isEmpty()) {
                mongo.remove(Query.query(Criteria.where("communityId").is(communityId)), Post.class);
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
            List<Post> wrappers = mongo.find(
                    Query.query(Criteria.where("repostOfId").in(originalIds))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(POST_BATCH_SIZE),
                    Post.class);
            if (wrappers.isEmpty()) return;
            List<String> wrapperIds = idsOf(wrappers);
            if (wrapperIds.isEmpty()) {
                mongo.remove(Query.query(Criteria.where("repostOfId").in(originalIds)), Post.class);
                continue;
            }
            deleteLegacyRepostPosts(wrapperIds);
            deletePostBatch(wrappers, wrapperIds);
        }
    }

    private void deletePostBatch(List<Post> posts, List<String> postIds) {
        // SQL notifications cannot participate in the Mongo transaction. Removing them first is
        // safe and idempotent; if Mongo then fails, the durable job retries the remaining cleanup.
        notifications.deleteByReferenceIdIn(postIds);
        notificationOutbox.deleteByReferenceIdIn(postIds);
        deleteCommentsInBatches(postIds);

        inMongoTransaction(() -> {
            Query byPost = Query.query(Criteria.where("postId").in(postIds));
            mongo.remove(byPost, Like.class);
            mongo.remove(byPost, Repost.class);
            mongo.remove(byPost, SavedPost.class);
            mongo.remove(byPost, PostRevision.class);
            mongo.remove(Query.query(new Criteria().andOperator(
                    Criteria.where("targetType").is("POST"),
                    Criteria.where("targetId").in(postIds))), ContentReport.class);
            mongo.remove(Query.query(Criteria.where("_id").in(postIds)), Post.class);

            // These durable tombstones are part of the same Mongo transaction as post deletion.
            // Cloudinary I/O is performed later by MediaAssetLifecycleService's retry worker.
            posts.forEach(mediaAssets::queuePostMediaDeletion);
            return null;
        });
    }

    private void deleteCommentsInBatches(Collection<String> postIds) {
        while (true) {
            List<Comment> comments = mongo.find(
                    Query.query(Criteria.where("postId").in(postIds))
                            .with(Sort.by(Sort.Direction.ASC, "_id"))
                            .limit(COMMENT_BATCH_SIZE),
                    Comment.class);
            if (comments.isEmpty()) return;
            List<String> commentIds = comments.stream()
                    .map(Comment::getId).filter(Objects::nonNull).toList();
            if (commentIds.isEmpty()) {
                mongo.remove(Query.query(Criteria.where("postId").in(postIds)), Comment.class);
                continue;
            }
            inMongoTransaction(() -> {
                mongo.remove(Query.query(Criteria.where("commentId").in(commentIds)),
                        CommentReaction.class);
                mongo.remove(Query.query(new Criteria().andOperator(
                        Criteria.where("targetType").is("COMMENT"),
                        Criteria.where("targetId").in(commentIds))), ContentReport.class);
                mongo.remove(Query.query(Criteria.where("_id").in(commentIds)), Comment.class);
                return null;
            });
        }
    }

    private List<String> idsOf(List<Post> posts) {
        return posts.stream().map(Post::getId).filter(Objects::nonNull).toList();
    }

    private <T> T inMongoTransaction(Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(work);
    }
}
