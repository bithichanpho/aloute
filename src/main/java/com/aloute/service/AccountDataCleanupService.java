package com.aloute.service;

import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.model.document.AccountSecurity;
import com.aloute.model.document.AccountCleanupJob;
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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mongo-side cleanup for account deletion. Chat history is retained but sender content/media is anonymized. */
@Service
public class AccountDataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AccountDataCleanupService.class);
    private final MongoTemplate mongo;
    private final MediaAssetLifecycleService mediaAssets;

    public AccountDataCleanupService(MongoTemplate mongo, MediaAssetLifecycleService mediaAssets) {
        this.mongo = mongo;
        this.mediaAssets = mediaAssets;
    }

    /** Persists cleanup work after a failed post-commit cleanup so a restart does not lose it. */
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
        log.warn("Persisted account cleanup retry (userId={}, cause={})",
                userId, job.getLastErrorType());
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void deleteUserData(Long userId, String previousAvatarUrl, String previousCoverUrl) {
        String id = String.valueOf(userId);
        List<Post> posts = mongo.find(Query.query(Criteria.where("userId").is(id)), Post.class);
        List<String> postIds = posts.stream().map(Post::getId).filter(java.util.Objects::nonNull).toList();
        List<Comment> authored = mongo.find(Query.query(Criteria.where("userId").is(id)), Comment.class);
        List<String> authoredChatMediaUrls = mongo.find(
                        Query.query(Criteria.where("senderId").is(id)), ChatMessage.class).stream()
                .map(ChatMessage::getMediaUrl)
                .filter(java.util.Objects::nonNull)
                .filter(url -> !url.isBlank())
                .distinct()
                .toList();
        List<Comment> postComments = postIds.isEmpty() ? List.of()
                : mongo.find(Query.query(Criteria.where("postId").in(postIds)), Comment.class);
        List<String> commentIds = new ArrayList<>();
        authored.stream().map(Comment::getId).filter(java.util.Objects::nonNull).forEach(commentIds::add);
        postComments.stream().map(Comment::getId).filter(java.util.Objects::nonNull).forEach(commentIds::add);

        if (!postIds.isEmpty()) {
            Query byPost = Query.query(Criteria.where("postId").in(postIds));
            mongo.remove(byPost, Like.class);
            mongo.remove(byPost, Comment.class);
            mongo.remove(byPost, Repost.class);
            mongo.remove(byPost, SavedPost.class);
            mongo.remove(Query.query(Criteria.where("postId").in(postIds)), PostRevision.class);
        }
        mongo.remove(Query.query(Criteria.where("userId").is(id)), Like.class);
        mongo.remove(Query.query(Criteria.where("userId").is(id)), Repost.class);
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), SavedPost.class);
        mongo.remove(Query.query(Criteria.where("userId").is(id)), Comment.class);

        Criteria commentReactionCriteria = commentIds.isEmpty()
                ? Criteria.where("userId").is(userId)
                : new Criteria().orOperator(Criteria.where("userId").is(userId), Criteria.where("commentId").in(commentIds));
        mongo.remove(Query.query(commentReactionCriteria), CommentReaction.class);
        mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("ownerId").is(userId), Criteria.where("targetId").is(userId))), UserBlock.class);
        mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("followerId").is(userId), Criteria.where("targetId").is(userId))), FollowRequest.class);
        mongo.remove(Query.query(Criteria.where("reporterId").is(userId)), ContentReport.class);
        mongo.remove(Query.query(Criteria.where("_id").is(userId)), AccountSecurity.class);
        mongo.remove(Query.query(Criteria.where("userId").is(id)), Post.class);

        // Preserve recipients' historical conversation, but remove media and identifying message text
        // for messages authored by the deleted account. Their sender profile resolves to the anonymized user row.
        mongo.updateMulti(Query.query(Criteria.where("senderId").is(id)),
                new Update().set("content", "Tin nhắn từ tài khoản đã xóa")
                        .unset("mediaUrl").unset("mediaName").unset("mediaSize"), ChatMessage.class);

        TransactionCommitHooks.afterCommit(() -> {
            for (Post post : posts) {
                try {
                    mediaAssets.deletePostMedia(post);
                } catch (RuntimeException error) {
                    log.warn("Unable to queue media cleanup for deleted account (postId={}, cause={})",
                            post.getId(), error.getClass().getSimpleName());
                }
            }
            for (String url : authoredChatMediaUrls) {
                deleteIfUnreferenced(url, "chat message");
            }
            deleteIfUnreferenced(previousAvatarUrl, "avatar");
            deleteIfUnreferenced(previousCoverUrl, "cover image");
        });
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
}
