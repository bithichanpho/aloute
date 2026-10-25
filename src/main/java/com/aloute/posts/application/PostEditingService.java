package com.aloute.posts.application;

import com.aloute.posts.api.*;
import com.aloute.posts.domain.PostAccess;
import com.aloute.posts.domain.ContentTags;
import com.aloute.identity.api.ActionRateLimit;
import com.aloute.media.api.PostMediaAttachments;
import com.aloute.moderation.api.ModerationPolicy;
import com.aloute.common.exception.*;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.PostVisibility;
import com.aloute.model.document.ModerationStatus;
import com.aloute.persistence.postgres.document.*;
import java.util.List;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Posts owns edit CAS, revision history, authorization and review transitions. */
@Service
public class PostEditingService implements PostEditing {
    private static final Logger log = LoggerFactory.getLogger(PostEditingService.class);
    private final PostgresDocumentStore documentStore;
    private final PostAccess access;
    private final ActionRateLimit limits;
    private final ModerationPolicy moderation;
    private final PostReviewDispatch moderationCoordinator;
    private final PostMediaAttachments postMediaAssets;
    private final ApplicationEventPublisher events;

    public PostEditingService(PostgresDocumentStore documentStore, PostAccess access,
            ActionRateLimit limits, ModerationPolicy moderation, PostReviewDispatch moderationCoordinator,
            PostMediaAttachments postMediaAssets, ApplicationEventPublisher events) {
        this.documentStore = documentStore;
        this.access = access;
        this.limits = limits;
        this.moderation = moderation;
        this.moderationCoordinator = moderationCoordinator;
        this.postMediaAssets = postMediaAssets;
        this.events = events;
    }

    public void edit(Long user, String id, PostEditCommand request) {
        if (user == null) throw new UnauthorizedException("Cần đăng nhập");
        PostVisibility requestedVisibility = PostVisibility.valueOf(request.visibility());
        limits.checkAndRecord("POST_EDIT", String.valueOf(user), 20,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofHours(1),
                "Bạn đã chỉnh sửa quá nhiều lần. Thử lại sau %d phút.",
                "Tạm khóa chỉnh sửa bài viết một giờ do vượt giới hạn.");
        Post post = access.requireReadable(id, user);
        if (!post.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được sửa bài");
        if (post.getModerationStatus() == ModerationStatus.HIDDEN)
            throw new ForbiddenException("Nội dung đã bị ẩn, không thể chỉnh sửa");
        if (java.util.Objects.equals(post.getContent(), request.content())
                && java.util.Objects.equals(post.getImageUrl(), request.imageUrl())
                && java.util.Objects.equals(post.getVideoUrl(), request.videoUrl())
                && java.util.Objects.equals(post.getVisibility(), requestedVisibility)) return;
        PostRevision revision = new PostRevision();
        revision.setPostId(id);
        revision.setContent(post.getContent());
        revision.setImageUrl(post.getImageUrl());
        revision.setVideoUrl(post.getVideoUrl());
        revision.setEditorId(user);
        revision.setVisibility(
                post.getVisibility() == null ? "PUBLIC" : post.getVisibility().name());
        if (postMediaAssets != null) {
            postMediaAssets.validatePostMediaUrl(request.imageUrl(), post.getImageUrl(), post.getUserId());
            postMediaAssets.validatePostMediaUrl(request.videoUrl(), post.getVideoUrl(), post.getUserId());
        }
        // Only a change to what moderators actually review (text or media) needs a new decision.
        // A visibility-only edit must neither re-approve nor "un-flag" a post.
        boolean reviewedContentChanged =
                !java.util.Objects.equals(post.getContent(), request.content())
                        || !java.util.Objects.equals(post.getImageUrl(), request.imageUrl())
                        || !java.util.Objects.equals(post.getVideoUrl(), request.videoUrl());
        boolean moderationRequired = moderation.isEnabled();
        boolean visibilityChanged = !java.util.Objects.equals(post.getVisibility(), requestedVisibility);
        var update = new Update()
                .set("content", request.content())
                .set("imageUrl", request.imageUrl())
                .set("videoUrl", request.videoUrl())
                .set("visibility", requestedVisibility)
                .set("hashtags", ContentTags.extract(request.content(), '#'))
                .set("mentions", ContentTags.extract(request.content(), '@'))
                .push("editHistory").slice(-100).each(revision)
                .set("updatedAt", java.time.LocalDateTime.now());
        if (reviewedContentChanged) {
            // Edited content goes back to the queue (fail closed). Without this an author could
            // publish harmless text, then edit it into violating text that stays APPROVED.
            // Demo mode (moderation disabled) cannot process the queue, so it keeps APPROVED.
            update.set("moderationStatus",
                            moderationRequired ? ModerationStatus.PENDING : ModerationStatus.APPROVED)
                    .set("toxicityScore", 0.0)
                    .set("moderationAttempts", 0)
                    .unset("moderationLastAttemptAt")
                    .unset("moderationFailureReason")
                    .unset("moderationLeaseUntil")
                    .set("notifyMentionsAfterModeration", moderationRequired);
        }
        var updated = documentStore.updateFirst(
                Query.query(Criteria.where("_id").is(id)
                        .and("updatedAt").is(post.getUpdatedAt())
                        .and("content").is(post.getContent())
                        // closes the race where an admin hides the post after the read above
                        .and("moderationStatus").ne(ModerationStatus.HIDDEN)),
                update, Post.class);
        if (updated.getMatchedCount() == 0)
            throw new BadRequestException("Bài viết vừa thay đổi. Hãy tải lại trước khi sửa.");
        if (postMediaAssets != null) {
            try {
                postMediaAssets.attachPostMedia(request.imageUrl(), post.getId(), post.getUserId());
                if (request.videoUrl() == null || !request.videoUrl().equals(request.imageUrl())) {
                    postMediaAssets.attachPostMedia(request.videoUrl(), post.getId(), post.getUserId());
                }
            } catch (RuntimeException trackingFailure) {
                log.warn("Post edit media attachment tracking deferred (postId={}, cause={})",
                        id, trackingFailure.getClass().getSimpleName());
            }
        }
        publishChange(id, user, visibilityChanged || (reviewedContentChanged && moderationRequired));
        if (reviewedContentChanged && moderationRequired) {
            if (moderationCoordinator != null) {
                TransactionCommitHooks.afterCommit(() -> {
                    try {
                        moderationCoordinator.submit(id);
                    } catch (RuntimeException dispatchFailure) {
                        // Scheduled recovery claims PENDING posts, so the edit is still reviewed.
                        log.warn("Edited post moderation dispatch deferred (postId={}, cause={})",
                                id, dispatchFailure.getClass().getSimpleName());
                    }
                });
            }
        }
    }

    public List<PostRevisionView> history(Long user, String post) {
        if (user == null) throw new UnauthorizedException("Cần đăng nhập");
        Post target = access.requireReadable(post, user);
        if (!target.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được xem lịch sử");
        if (target.getEditHistory() == null) return List.of();
        return target.getEditHistory().stream()
                .sorted(Comparator.comparing(PostRevision::getCreatedAt).reversed())
                .limit(100)
                .map(revision -> new PostRevisionView(revision.getId(), revision.getPostId(),
                        revision.getContent(), revision.getImageUrl(), revision.getVideoUrl(),
                        revision.getVisibility(), revision.getEditorId(), revision.getCreatedAt()))
                .toList();
    }


    private void publishChange(String postId, Long actorId, boolean invalidateAll) {
        PostChanged event = PostChanged.edited(postId, actorId, invalidateAll);
        TransactionCommitHooks.afterCommit(() -> {
            try { events.publishEvent(event); }
            catch (RuntimeException failure) {
                log.warn("Post edit cache hint failed (postId={}, cause={}); cache TTL remains the fallback",
                        postId, failure.getClass().getSimpleName());
            }
        });
    }
}
