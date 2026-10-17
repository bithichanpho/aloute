package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Keeps a comment, post counter and parent reply counter atomic within MongoDB. */
@Service
public class CommentPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CommentPersistenceService.class);

    private final ICommentRepository comments;
    private final PostService posts;
    private final MongoTemplate mongo;
    private final NotificationService notifications;
    private final MongoTransactionRetryExecutor transactionRetryExecutor;

    @Autowired
    public CommentPersistenceService(
            ICommentRepository comments,
            PostService posts,
            MongoTemplate mongo,
            NotificationService notifications,
            MongoTransactionRetryExecutor transactionRetryExecutor) {
        this.comments = comments;
        this.posts = posts;
        this.mongo = mongo;
        this.notifications = notifications;
        this.transactionRetryExecutor = transactionRetryExecutor;
    }

    /** Compatibility constructor for service tests which do not start Spring transactions. */
    public CommentPersistenceService(
            ICommentRepository comments,
            PostService posts,
            MongoTemplate mongo,
            NotificationService notifications) {
        this(comments, posts, mongo, notifications, null);
    }

    public Comment create(
            String postId,
            Long userId,
            CreateCommentRequest request,
            ModerationStatus moderationStatus,
            String requestedParentId) {
        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        try {
            if (transactionRetryExecutor == null) {
                return createInTransaction(postId, userId, request, moderationStatus, requestedParentId);
            }
            return transactionRetryExecutor.execute(
                    () -> createInTransaction(postId, userId, request, moderationStatus, requestedParentId));
        } catch (DuplicateKeyException duplicateRequest) {
            if (clientRequestId == null) throw duplicateRequest;
            Comment committed = comments.findById(idempotentCommentId(postId, userId, clientRequestId))
                    .orElseThrow(() -> duplicateRequest);
            verifySameCreateRequest(committed, postId, userId, request, requestedParentId);
            return committed;
        }
    }

    private Comment createInTransaction(
            String postId,
            Long userId,
            CreateCommentRequest request,
            ModerationStatus moderationStatus,
            String requestedParentId) {
        Post post = posts.requireReadablePost(postId, userId);
        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        String stableCommentId = clientRequestId == null
                ? null : idempotentCommentId(postId, userId, clientRequestId);
        if (stableCommentId != null) {
            Comment existing = comments.findById(stableCommentId).orElse(null);
            if (existing != null) {
                // Resolve a retry before validating a parent that may since have been hidden/deleted.
                verifySameCreateRequest(existing, postId, userId, request, requestedParentId);
                return existing;
            }
        }

        Comment parent = null;
        if (requestedParentId != null && !requestedParentId.isBlank()) {
            parent = comments.findById(requestedParentId.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận gốc để trả lời"));
            if (!postId.equals(parent.getPostId())) {
                throw new ResourceNotFoundException("Bình luận không thuộc bài viết này");
            }
            if (parent.getModerationStatus() != null
                    && parent.getModerationStatus() != ModerationStatus.APPROVED) {
                throw new ResourceNotFoundException("Không tìm thấy bình luận gốc để trả lời");
            }
        }

        Comment comment = new Comment();
        if (stableCommentId != null) comment.setId(stableCommentId);
        comment.setPostId(postId);
        comment.setUserId(String.valueOf(userId));
        comment.setContent(request.getContent());
        comment.setModerationStatus(moderationStatus);
        comment.setParentCommentId(parent == null ? null : parent.getId());
        boolean countersApplied = moderationStatus == ModerationStatus.APPROVED;
        comment.setModerationCountersApplied(countersApplied);
        comment.setNotifyAfterModeration(moderationStatus == ModerationStatus.PENDING);

        Comment saved = comments.save(comment);
        if (countersApplied) posts.adjustCommentCount(postId, 1);

        if (parent != null) {
            if (countersApplied) {
                mongo.updateFirst(
                        Query.query(Criteria.where("_id").is(parent.getId())),
                        new Update().inc("replyCount", 1),
                        Comment.class);
                Long parentAuthorId = Long.valueOf(parent.getUserId());
                if (!parentAuthorId.equals(userId)) {
                    notifyAfterCommit(parentAuthorId, userId, postId, "đã trả lời bình luận của bạn");
                }
            }
        } else if (countersApplied) {
            notifyAfterCommit(Long.valueOf(post.getUserId()), userId, postId,
                    "đã bình luận về bài viết của bạn");
        }
        return saved;
    }


    /**
     * Replaces an approved comment with a pending edit without leaving public counters inflated
     * while the edited content is hidden from everyone except its author. A write conflict retries
     * in a new Mongo transaction, just like comment creation and deletion.
     */
    public Comment updatePendingForModeration(Comment comment, String normalizedContent) {
        if (transactionRetryExecutor == null) {
            return updatePendingForModerationInTransaction(comment, normalizedContent);
        }
        return transactionRetryExecutor.execute(
                () -> updatePendingForModerationInTransaction(comment, normalizedContent));
    }

    private Comment updatePendingForModerationInTransaction(Comment comment, String normalizedContent) {
        if (comment == null) throw new IllegalArgumentException("comment is required");
        ModerationStatus oldStatus = comment.getModerationStatus();
        boolean publiclyCounted = comment.isModerationCountersApplied()
                && (oldStatus == null || oldStatus == ModerationStatus.APPROVED);
        Criteria oldStatusCriteria = oldStatus == null
                ? Criteria.where("moderationStatus").is(null)
                : Criteria.where("moderationStatus").is(oldStatus);
        Query unchanged = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(comment.getId()),
                Criteria.where("content").is(comment.getContent()),
                oldStatusCriteria));
        Update update = new Update()
                .set("content", normalizedContent)
                .set("moderationStatus", ModerationStatus.PENDING)
                .set("moderationAttempts", 0)
                .unset("moderationLastAttemptAt")
                .unset("moderationLeaseUntil")
                .unset("moderationFailureReason")
                .set("notifyAfterModeration", false);
        if (publiclyCounted) update.set("moderationCountersApplied", false);
        if (mongo.updateFirst(unchanged, update, Comment.class).getMatchedCount() == 0) {
            throw new BadRequestException("Bình luận vừa thay đổi. Hãy tải lại trước khi sửa.");
        }
        if (publiclyCounted) {
            posts.adjustCommentCount(comment.getPostId(), -1);
            if (comment.getParentCommentId() != null && !comment.getParentCommentId().isBlank()) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(comment.getParentCommentId())),
                        new Update().inc("replyCount", -1), Comment.class);
            }
        }
        comment.setContent(normalizedContent);
        comment.setModerationStatus(ModerationStatus.PENDING);
        comment.setModerationAttempts(0);
        comment.setModerationLastAttemptAt(null);
        comment.setModerationLeaseUntil(null);
        comment.setModerationFailureReason(null);
        comment.setNotifyAfterModeration(false);
        if (publiclyCounted) comment.setModerationCountersApplied(false);
        return comment;
    }

    private String normalizeClientRequestId(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String idempotentCommentId(String postId, Long userId, String clientRequestId) {
        String material = userId + ":" + postId + ":" + clientRequestId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "idem-comment-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private void verifySameCreateRequest(
            Comment existing,
            String postId,
            Long userId,
            CreateCommentRequest request,
            String requestedParentId) {
        String expectedParentId = requestedParentId == null || requestedParentId.isBlank()
                ? null : requestedParentId.trim();
        if (!Objects.equals(existing.getPostId(), postId)
                || !Objects.equals(existing.getUserId(), String.valueOf(userId))
                || !Objects.equals(existing.getContent(), request.getContent())
                || !Objects.equals(existing.getParentCommentId(), expectedParentId)) {
            throw new BadRequestException(
                    "Mã yêu cầu bình luận đã được dùng cho nội dung khác. Hãy tạo yêu cầu mới.");
        }
    }

    private void notifyAfterCommit(Long receiverId, Long senderId, String postId, String message) {
        TransactionCommitHooks.afterCommit(() -> {
            try {
                notifications.notify(receiverId, senderId, "COMMENT", postId, message);
            } catch (RuntimeException ex) {
                log.warn("Comment notification enqueue failed (postId={}, cause={})",
                        postId, ex.getClass().getSimpleName());
            }
        });
    }
}
