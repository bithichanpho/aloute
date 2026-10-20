package com.aloute.service;

import com.aloute.model.document.AccountCleanupJob;
import com.aloute.model.document.AccountSecurity;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.Comment;
import com.aloute.model.document.CommentReaction;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.FollowRequest;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.Repost;
import com.aloute.model.document.SavedPost;
import com.aloute.model.document.UserBlock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Mongo-side cleanup for account deletion. Chat history is retained but sender content/media is anonymized. */
@Service
public class AccountDataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AccountDataCleanupService.class);
    private static final int POST_BATCH_SIZE = 100;
    private static final int COMMENT_BATCH_SIZE = 500;
    private static final int CHAT_BATCH_SIZE = 250;

    private final MongoTemplate mongo;
    private final MediaAssetLifecycleService mediaAssets;
    private final MongoTransactionRetryExecutor transactions;

    @Autowired
    public AccountDataCleanupService(
            MongoTemplate mongo,
            MediaAssetLifecycleService mediaAssets,
            MongoTransactionRetryExecutor transactions) {
        this.mongo = mongo;
        this.mediaAssets = mediaAssets;
        this.transactions = transactions;
    }

    /** Compatibility constructor for isolated unit tests. */
    public AccountDataCleanupService(MongoTemplate mongo, MediaAssetLifecycleService mediaAssets) {
        this(mongo, mediaAssets, null);
    }

    /** Persists cleanup work after a failed cleanup so a restart does not lose it. */
    public void enqueueRetry(Long userId, String previousAvatarUrl, String previousCoverUrl, RuntimeException failure) {
        if (userId == null) throw new IllegalArgumentException("userId is required for account cleanup");
        String jobId = String.valueOf(userId);
        AccountCleanupJob job = mongo.findById(jobId, AccountCleanupJob.class);
        if (job == null) {
            job = new AccountCleanupJob();
            job.setId(jobId);
            job.setUserId(userId);
            job.setCreatedAt(Instant.now());
            job.setAttemptCount(0);
        }
        job.setPreviousAvatarUrl(previousAvatarUrl);
        job.setPreviousCoverUrl(previousCoverUrl);
        job.setLastErrorType(failure == null ? "UnknownFailure" : failure.getClass().getSimpleName());
        job.setNextAttemptAt(Instant.now());
        mongo.save(job);
        log.warn("Persisted account cleanup retry (userId={}, cause={})", userId, job.getLastErrorType());
    }

    /**
     * Idempotent, bounded cleanup. Each pass only loads a small batch, so deleting an old and very
     * active account cannot exhaust the heap or hold one oversized Mongo transaction open.
     */
    public void deleteUserData(Long userId, String previousAvatarUrl, String previousCoverUrl) {
        if (userId == null) throw new IllegalArgumentException("userId is required for account cleanup");
        String id = String.valueOf(userId);

        deletePostsInBatches(id);
        deleteCommentsInBatches(Criteria.where("userId").is(id));

        mongo.remove(Query.query(Criteria.where("userId").is(id)), Like.class);
        mongo.remove(Query.query(Criteria.where("userId").is(id)), Repost.class);
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), SavedPost.class);
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), CommentReaction.class);
        mongo.remove(Query.query(new Criteria().orOperator(
                Criteria.where("ownerId").is(userId), Criteria.where("targetId").is(userId))), UserBlock.class);
        mongo.remove(Query.query(new Criteria().orOperator(
                Criteria.where("followerId").is(userId), Criteria.where("targetId").is(userId))), FollowRequest.class);
        mongo.remove(Query.query(Criteria.where("reporterId").is(userId)), ContentReport.class);
        mongo.remove(Query.query(Criteria.where("_id").is(userId)), AccountSecurity.class);

        anonymizeChatInBatches(id);
        deleteIfUnreferenced(previousAvatarUrl, "avatar");
        deleteIfUnreferenced(previousCoverUrl, "cover image");
    }

    private void deletePostsInBatches(String userId) {
        while (true) {
            Query batchQuery = Query.query(Criteria.where("userId").is(userId))
                    .with(Sort.by(Sort.Direction.ASC, "_id"))
                    .limit(POST_BATCH_SIZE);
            List<Post> posts = mongo.find(batchQuery, Post.class);
            if (posts.isEmpty()) return;

            List<String> postIds = posts.stream().map(Post::getId).filter(Objects::nonNull).toList();
            if (postIds.isEmpty()) {
                mongo.remove(batchQuery, Post.class);
                continue;
            }

            deleteCommentsInBatches(Criteria.where("postId").in(postIds));
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
                posts.forEach(mediaAssets::queuePostMediaDeletion);
                return null;
            });
        }
    }

    private void deleteCommentsInBatches(Criteria criteria) {
        while (true) {
            Query batchQuery = Query.query(criteria)
                    .with(Sort.by(Sort.Direction.ASC, "_id"))
                    .limit(COMMENT_BATCH_SIZE);
            List<Comment> comments = mongo.find(batchQuery, Comment.class);
            if (comments.isEmpty()) return;
            List<String> ids = comments.stream().map(Comment::getId).filter(Objects::nonNull).toList();
            if (ids.isEmpty()) {
                mongo.remove(batchQuery, Comment.class);
                continue;
            }
            inMongoTransaction(() -> {
                mongo.remove(Query.query(Criteria.where("commentId").in(ids)), CommentReaction.class);
                mongo.remove(Query.query(new Criteria().andOperator(
                        Criteria.where("targetType").is("COMMENT"),
                        Criteria.where("targetId").in(ids))), ContentReport.class);
                mongo.remove(Query.query(Criteria.where("_id").in(ids)), Comment.class);
                return null;
            });
        }
    }

    private void anonymizeChatInBatches(String senderId) {
        Criteria authoredWithMedia = new Criteria().andOperator(
                Criteria.where("senderId").is(senderId),
                Criteria.where("mediaUrl").nin(null, ""));
        while (true) {
            Query batchQuery = Query.query(authoredWithMedia)
                    .with(Sort.by(Sort.Direction.ASC, "_id"))
                    .limit(CHAT_BATCH_SIZE);
            List<ChatMessage> messages = mongo.find(batchQuery, ChatMessage.class);
            if (messages.isEmpty()) break;
            List<String> messageIds = messages.stream().map(ChatMessage::getId).filter(Objects::nonNull).toList();
            if (messageIds.isEmpty()) {
                mongo.updateMulti(batchQuery, new Update().unset("mediaUrl"), ChatMessage.class);
                continue;
            }
            mongo.updateMulti(Query.query(Criteria.where("_id").in(messageIds)),
                    new Update().set("content", "Tin nhắn từ tài khoản đã xóa")
                            .unset("mediaUrl").unset("mediaType").unset("mediaName").unset("mediaSize"),
                    ChatMessage.class);
            messages.stream().map(ChatMessage::getMediaUrl).filter(Objects::nonNull)
                    .filter(url -> !url.isBlank()).distinct()
                    .forEach(url -> deleteIfUnreferenced(url, "chat message"));
        }

        mongo.updateMulti(Query.query(Criteria.where("senderId").is(senderId)),
                new Update().set("content", "Tin nhắn từ tài khoản đã xóa")
                        .unset("mediaUrl").unset("mediaType").unset("mediaName").unset("mediaSize"),
                ChatMessage.class);
    }

    private void deleteIfUnreferenced(String url, String source) {
        if (url == null || url.isBlank()) return;
        try {
            mediaAssets.deleteIfUnreferenced(url);
        } catch (RuntimeException error) {
            log.warn("Unable to queue media cleanup for deleted account (source={}, cause={})",
                    source, error.getClass().getSimpleName());
        }
    }

    private <T> T inMongoTransaction(java.util.function.Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(work);
    }
}
