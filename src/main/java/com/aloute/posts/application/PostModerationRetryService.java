package com.aloute.posts.application;

import com.aloute.common.exception.*;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.identity.api.ActionRateLimit;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.moderation.api.ModerationPolicy;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.api.PostReviewDispatch;
import com.aloute.posts.domain.PostAccess;
import com.aloute.posts.domain.PostReviewRetryPolicy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PostModerationRetryService {
    private static final Logger log = LoggerFactory.getLogger(PostModerationRetryService.class);
    private final PostgresDocumentStore store;
    private final PostAccess access;
    private final ActionRateLimit limits;
    private final ModerationPolicy moderation;
    private final PostReviewDispatch dispatch;

    public PostModerationRetryService(PostgresDocumentStore store, PostAccess access,
            ActionRateLimit limits, ModerationPolicy moderation, PostReviewDispatch dispatch) {
        this.store = store; this.access = access; this.limits = limits;
        this.moderation = moderation; this.dispatch = dispatch;
    }

    public void retry(Long actor, String id) {
        if (actor == null) throw new UnauthorizedException("Cần đăng nhập");
        Post post = access.requireReadable(id, actor);
        if (!actor.toString().equals(post.getUserId()))
            throw new ForbiddenException("Chỉ tác giả được yêu cầu duyệt lại");
        // A repeated click must not reset the lease/attempts of a running job.
        if (post.getModerationStatus() == ModerationStatus.PENDING) return;
        if (!PostReviewRetryPolicy.canRetry(post))
            throw new BadRequestException("Bài viết này không có lỗi kết nối kiểm duyệt để thử lại");
        if (!moderation.isEnabled())
            throw new BadRequestException("Cần bật dịch vụ kiểm duyệt trước khi thử lại");
        limits.checkAndRecord("POST_MODERATION_RETRY", actor.toString(), 5,
                Duration.ofHours(1), Duration.ofHours(1),
                "Bạn đã thử lại quá nhiều lần. Thử lại sau %d phút.", "Vui lòng thử lại sau một giờ.");
        // CAS protects simultaneous retries, edits and administrator decisions. No audience,
        // content or community approval fields are changed, and this never approves a post.
        Query unchanged = Query.query(Criteria.where("_id").is(id)
                .and("userId").is(post.getUserId())
                .and("moderationStatus").is(ModerationStatus.FLAGGED)
                .and("moderationFailureReason").is(post.getModerationFailureReason())
                .and("moderationLastAttemptAt").is(post.getModerationLastAttemptAt())
                .and("updatedAt").is(post.getUpdatedAt())
                .and("content").is(post.getContent())
                .and("imageUrl").is(post.getImageUrl())
                .and("videoUrl").is(post.getVideoUrl()));
        var result = store.updateFirst(unchanged, new Update()
                .set("moderationStatus", ModerationStatus.PENDING)
                .set("moderationAttempts", 0)
                .unset("moderationLeaseUntil").unset("moderationLastAttemptAt")
                .unset("moderationFailureReason"), Post.class);
        if (result.getModifiedCount() == 0)
            throw new BadRequestException("Bài viết vừa thay đổi. Hãy tải lại trước khi thử lại.");
        TransactionCommitHooks.afterCommit(() -> {
            try { dispatch.submit(id); }
            catch (RuntimeException failure) {
                // The existing durable PENDING scan recovers rejected dispatches after restart.
                log.warn("Post moderation retry dispatch deferred (postId={}, cause={})",
                        id, failure.getClass().getSimpleName());
            }
        });
    }
}
