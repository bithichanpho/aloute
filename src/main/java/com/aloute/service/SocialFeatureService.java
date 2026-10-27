package com.aloute.service;

import com.aloute.notifications.api.Notifications;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.SocialCursorPage;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.FollowRequest;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.UserBlock;
import com.aloute.model.entity.Friend;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;


import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SocialFeatureService {
    private static final Logger log = LoggerFactory.getLogger(SocialFeatureService.class);
    private final PostgresDocumentStore documentStore;
    private final ContentAccessService access;
    private final com.aloute.users.api.UserDirectory users;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;
    private final PostService posts;
    private final AuthRateLimitService limits;
    private final Notifications notificationService;
    private final com.aloute.administration.api.AccountModeration accountModeration;
    private final com.aloute.users.api.UserAccountAdministration accountAdministration;
    private final MediaAssetLifecycleService postMediaAssets;
    private final com.aloute.posts.api.PostEditing postEditing;
    private final com.aloute.posts.api.SavedPosts savedPosts;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aloute.social.FollowRequestRealtimePublisher followRequestRealtimePublisher;

    @org.springframework.beans.factory.annotation.Autowired
    public SocialFeatureService(
            PostgresDocumentStore documentStore,
            ContentAccessService access,
            com.aloute.users.api.UserDirectory users,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            PostService posts,
            AuthRateLimitService limits,
            Notifications notificationService,
            com.aloute.administration.api.AccountModeration accountModeration,
            com.aloute.users.api.UserAccountAdministration accountAdministration,
            MediaAssetLifecycleService postMediaAssets,
            com.aloute.posts.api.PostEditing postEditing, com.aloute.posts.api.SavedPosts savedPosts) {
        this.documentStore = documentStore;
        this.access = access;
        this.users = users;
        this.friends = friends;
        this.closeFriends = closeFriends;
        this.posts = posts;
        this.limits = limits;
        this.notificationService = notificationService;
        this.accountModeration = accountModeration;
        this.accountAdministration = accountAdministration;
        this.postMediaAssets = postMediaAssets;
        this.postEditing = postEditing;
        this.savedPosts = savedPosts;
    }

    /** Legacy construction adapter; production injects Posts public use cases. */
    public SocialFeatureService(
            PostgresDocumentStore documentStore,
            ContentAccessService access,
            IUserRepository users,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            PostService posts,
            ModerationService moderation,
            AuthRateLimitService limits,
            Notifications notificationService,
            PostModerationCoordinator moderationCoordinator,
            RefreshTokenService refreshTokens,
            AuthenticationUserCache authenticationUserCache,
            MediaAssetLifecycleService postMediaAssets) {
        this(documentStore, access, new com.aloute.users.infrastructure.JpaUserDirectory(users),
                friends, closeFriends, posts, limits, notificationService,
                new com.aloute.administration.application.AccountModerationService(
                        new com.aloute.users.infrastructure.JpaUserAccountAdministration(users),
                        new com.aloute.identity.infrastructure.LegacySessionInvalidation(refreshTokens, authenticationUserCache)),
                new com.aloute.users.infrastructure.JpaUserAccountAdministration(users), postMediaAssets,
                new com.aloute.posts.application.PostEditingService(documentStore,
                        new com.aloute.posts.infrastructure.LegacyPostAccess(access), limits,
                        moderation == null ? () -> false : moderation, moderationCoordinator, postMediaAssets,
                        event -> {
                            if (event instanceof com.aloute.posts.api.PostChanged changed) {
                                if (changed.invalidateAll()) posts.invalidateAllFeedCache();
                                posts.invalidateViewerFeedCache(changed.actorId());
                            }
                        }),
                new com.aloute.posts.application.SavedPostsService(documentStore,
                        new com.aloute.posts.infrastructure.LegacyPostAccess(access), posts));
    }

    /** Compatibility constructor retained for focused service tests. */
    public SocialFeatureService(
            PostgresDocumentStore documentStore, ContentAccessService access, IUserRepository users,
            IFriendRepository friends, ICloseFriendRepository closeFriends, PostService posts,
            ModerationService moderation, AuthRateLimitService limits, Notifications notificationService) {
        this(documentStore, access, users, friends, closeFriends, posts, moderation, limits,
                notificationService, null, null, null, null);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = "suggestedUsers", key = "#owner + ':' + T(com.aloute.service.DiscoveryService).FEED_SUGGESTION_LIMIT")
    public void block(Long owner, Long target, boolean blocked) {
        if (owner.equals(target)) throw new BadRequestException("Không thể tự chặn chính mình");
        if (!users.exists(target))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
        String id = owner + ":" + target;
        if (!blocked) {
            documentStore.remove(Query.query(Criteria.where("_id").is(id)), UserBlock.class);
            // Unblocking must not accidentally restore a relationship whose cleanup failed
            // during the original block operation.
            afterCommitSafely("unblock relationship cleanup", () -> removeRelationshipEdges(owner, target));
            posts.invalidateViewerFeedCache(owner);
            posts.invalidateViewerFeedCache(target);
            return;
        }
        UserBlock block = new UserBlock();
        block.setId(id);
        block.setOwnerId(owner);
        block.setTargetId(target);
        block.setRelationshipsSynced(false);
        documentStore.save(block);
        documentStore.remove(
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("followerId").is(owner).and("targetId").is(target),
                                        Criteria.where("followerId").is(target).and("targetId").is(owner))),
                FollowRequest.class);

        // PostgreSQL stores the block as the security source of truth. Remove PostgreSQL relationship
        // rows only after the block transaction commits. A scheduled reconciliation repairs this
        // cross-store cleanup if SQL becomes temporarily unavailable.
        afterCommitSafely("block relationship cleanup", () -> cleanupBlockRelationshipsAndMarkSynced(id, owner, target));
        posts.invalidateViewerFeedCache(owner);
        posts.invalidateViewerFeedCache(target);
    }

    public List<com.aloute.dto.response.BlockedAccountResponse> blocks(Long user) {
        return blockResponses(documentStore.find(
                Query.query(Criteria.where("ownerId").is(user)).limit(200), UserBlock.class));
    }

    public SocialCursorPage<com.aloute.dto.response.BlockedAccountResponse> blocks(Long user, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        SocialCursor point = decodeSocialCursor(cursor);
        Query query = Query.query(Criteria.where("ownerId").is(user));
        if (point != null) query.addCriteria(Criteria.keysetBefore("createdAt", point.createdAt(), "_id", point.id()));
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<UserBlock> items = new java.util.ArrayList<>(documentStore.find(query, UserBlock.class));
        boolean hasMore = items.size() > size;
        if (hasMore) items.subList(size, items.size()).clear();
        String next = hasMore && !items.isEmpty()
                ? encodeSocialCursor(items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getId())
                : "";
        return new SocialCursorPage<>(blockResponses(items), next, hasMore);
    }

    private List<com.aloute.dto.response.BlockedAccountResponse> blockResponses(List<UserBlock> blocks) {
        if (blocks.isEmpty()) return List.of();
        var labels = users.profileLabels(blocks.stream().map(UserBlock::getTargetId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        return blocks.stream().map(block -> {
            var label = block.getTargetId() == null ? null : labels.get(block.getTargetId());
            return new com.aloute.dto.response.BlockedAccountResponse(block.getId(), block.getOwnerId(),
                    block.getTargetId(), block.getCreatedAt(), label == null ? null : label.username(),
                    label == null ? null : label.fullName(),
                    com.aloute.common.util.AvatarUtil.resolve(label == null ? null : label.avatarUrl()));
        }).toList();
    }

    public List<com.aloute.dto.response.FollowRequestResponse> requests(Long user) {
        return requestResponses(documentStore.find(
                Query.query(Criteria.where("targetId").is(user)).limit(200), FollowRequest.class));
    }

    public long countRequests(Long user) {
        return documentStore.count(Query.query(Criteria.where("targetId").is(user)), FollowRequest.class);
    }

    public long countUnreadRequests(Long user) {
        return documentStore.count(Query.query(
                Criteria.where("targetId").is(user).and("seenAt").is(null)), FollowRequest.class);
    }

    /** Opening the inbox acknowledges existing requests without deciding or deleting them. */
    @Transactional
    public void markRequestsSeen(Long user) {
        if (user == null) throw new com.aloute.common.exception.UnauthorizedException("Bạn cần đăng nhập");
        var result = documentStore.updateMulti(Query.query(
                Criteria.where("targetId").is(user).and("seenAt").is(null)),
                new Update().set("seenAt", Instant.now()), FollowRequest.class);
        if (result.getModifiedCount() > 0) {
            afterCommitSafely("follow-request inbox seen realtime", () -> {
                if (followRequestRealtimePublisher != null) followRequestRealtimePublisher.publishSeen(user);
            });
        }
    }

    public SocialCursorPage<com.aloute.dto.response.FollowRequestResponse> requests(
            Long user, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        SocialCursor point = decodeSocialCursor(cursor);
        Query query = Query.query(Criteria.where("targetId").is(user));
        if (point != null) query.addCriteria(Criteria.keysetBefore("createdAt", point.createdAt(), "_id", point.id()));
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<FollowRequest> items = new java.util.ArrayList<>(documentStore.find(query, FollowRequest.class));
        boolean hasMore = items.size() > size;
        if (hasMore) items.subList(size, items.size()).clear();
        String next = hasMore && !items.isEmpty()
                ? encodeSocialCursor(items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getId())
                : "";
        return new SocialCursorPage<>(requestResponses(items), next, hasMore);
    }

    private List<com.aloute.dto.response.FollowRequestResponse> requestResponses(List<FollowRequest> requests) {
        if (requests.isEmpty()) return List.of();
        var labels = users.profileLabels(requests.stream().map(FollowRequest::getFollowerId).distinct().toList());
        return requests.stream().map(request -> {
            var label = labels.get(request.getFollowerId());
            return new com.aloute.dto.response.FollowRequestResponse(request.getId(), request.getFollowerId(),
                    request.getTargetId(), request.getCreatedAt(), label == null ? null : label.username(),
                    label == null ? null : label.fullName(),
                    com.aloute.common.util.AvatarUtil.resolve(label == null ? null : label.avatarUrl()));
        }).toList();
    }

    private String encodeSocialCursor(java.time.Instant createdAt, String id) {
        String value = createdAt + "|" + id;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private SocialCursor decodeSocialCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 512) throw new BadRequestException("Cursor không hợp lệ");
        try {
            String value = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            int separator = value.indexOf('|');
            if (separator <= 0 || separator == value.length() - 1) throw new IllegalArgumentException();
            java.time.Instant createdAt = java.time.Instant.parse(value.substring(0, separator));
            String id = value.substring(separator + 1);
            if (id.length() > 150) throw new IllegalArgumentException();
            return new SocialCursor(createdAt, id);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Cursor không hợp lệ");
        }
    }

    private record SocialCursor(java.time.Instant createdAt, String id) {}

    @Transactional
    public void decideFollow(Long user, String id, boolean accept) {
        FollowRequest request = documentStore.findById(id, FollowRequest.class);
        if (request == null || !user.equals(request.getTargetId()))
            throw new ResourceNotFoundException("Không tìm thấy yêu cầu");
        access.requireProfile(user, request.getFollowerId());
        if (!accept) {
            // Declining only changes the request document; push a realtime event so the recipient's
            // nav badge and the sender's follow button update without waiting for polling.
            documentStore.remove(Query.query(Criteria.where("_id").is(request.getId())), FollowRequest.class);
            afterCommitSafely("follow-request rejection realtime", () -> publishFollowRequestDecision(user, request, false));
            return;
        }
        if (!friends.existsByFollowerIdAndFollowedId(request.getFollowerId(), user)) {
            friends.save(new Friend(request.getFollowerId(), user));
        }
        // If this PostgreSQL cleanup fails after the SQL commit, reconciliation observes the persisted
        // Friend edge and safely removes the stale FollowRequest later.
        afterCommitSafely("follow-request cleanup", () -> {
            try {
                documentStore.remove(Query.query(Criteria.where("_id").is(request.getId())), FollowRequest.class);
            } catch (RuntimeException cleanupFailure) {
                // The accepted SQL follow edge is authoritative; reconciliation removes stale request
                // documents later. Still publish the decision and queue the requester's notice now.
                log.warn("Could not remove accepted follow-request document (requestId={}, cause={})",
                        request.getId(), cleanupFailure.getClass().getSimpleName());
            }
            publishFollowRequestDecision(user, request, true);
            notificationService.notifyFollowRequestAccepted(request.getFollowerId(), user);
        });
        posts.invalidateViewerFeedCache(user);
        posts.invalidateViewerFeedCache(request.getFollowerId());
    }

    private void publishFollowRequestDecision(Long targetUserId, FollowRequest request, boolean accepted) {
        if (followRequestRealtimePublisher == null || request == null) return;
        followRequestRealtimePublisher.publish(
                targetUserId, "REQUEST_DECIDED", request.getFollowerId(), targetUserId, "NONE");
        followRequestRealtimePublisher.publish(
                request.getFollowerId(), "STATE_CHANGED", request.getFollowerId(), targetUserId,
                accepted ? "FOLLOWING" : "NONE");
    }

    public ContentReport report(Long user, String type, String id, String reason) {
        limits.checkAndRecord(
                "REPORT",
                user.toString(),
                20,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(30),
                "Thử lại sau %d phút",
                "Bạn đã gửi quá nhiều báo cáo");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new BadRequestException("Lý do cần 1–1000 ký tự");
        switch (type) {
            case "POST" -> access.requirePost(id, user);
            case "COMMENT" -> {
                Comment comment = documentStore.findById(id, Comment.class);
                if (comment == null)
                    throw new ResourceNotFoundException("Không tìm thấy bình luận");
                access.requirePost(comment.getPostId(), user);
            }
            case "USER" -> access.requireProfile(user, Long.valueOf(id));
            default -> throw new BadRequestException("Loại báo cáo không hợp lệ");
        }
        ContentReport report = new ContentReport();
        report.setId(user + ":" + type + ":" + id);
        ContentReport existing = documentStore.findById(report.getId(), ContentReport.class);
        if (existing != null) return existing;
        report.setReporterId(user);
        report.setTargetType(type);
        report.setTargetId(id);
        report.setReason(reason.trim());
        return documentStore.insert(report);
    }

    public void requireAdmin(Long user) {
        accountAdministration.requireAdministrator(user);
    }

    public Map<String, Object> queue(Long user) {
        return queue(user, 0, 20);
    }

    /**
     * A stable admin queue page. Each category is sorted newest-first with _id as a tie-breaker;
     * an extra record determines whether a next page exists without an expensive full count.
     */
    public Map<String, Object> queue(Long user, int requestedPage, int requestedSize) {
        requireAdmin(user);
        int page = Math.max(0, Math.min(10_000, requestedPage));
        int size = Math.max(1, Math.min(50, requestedSize));
        long offset = (long) page * size;
        var descendingCreated = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"));
        QueueSlice<ContentReport> reports = queueSlice(
                Query.query(Criteria.where("status").is("OPEN")), ContentReport.class,
                descendingCreated, offset, size);
        List<Post> flaggedPosts = documentStore.find(
                Query.query(Criteria.where("moderationStatus").is(ModerationStatus.FLAGGED))
                        .with(descendingCreated).limit(size), Post.class);
        return Map.of(
                "reports", reports.items(),
                "flaggedPosts", flaggedPosts,
                "posts", List.of(),
                "comments", List.of(),
                "page", page,
                "size", size,
                "hasMore", reports.hasMore());
    }

    private <T> QueueSlice<T> queueSlice(Query base, Class<T> type, Sort sort, long offset, int size) {
        base.with(sort).skip(offset).limit(size + 1);
        List<T> found = documentStore.find(base, type);
        boolean hasMore = found.size() > size;
        if (hasMore) found = found.subList(0, size);
        return new QueueSlice<>(List.copyOf(found), hasMore);
    }

    private record QueueSlice<T>(List<T> items, boolean hasMore) {}

    public Object reviewContent(Long user, String type, String id) {
        requireAdmin(user);
        if (type.equals("POST")) return documentStore.findById(id, Post.class);
        if (type.equals("COMMENT")) return documentStore.findById(id, Comment.class);
        if (type.equals("USER")) {
            var account = accountAdministration.reviewAccount(user, Long.valueOf(id));
            return Map.of("username", account.username(), "fullName", account.fullName(), "active", account.active());
        }
        throw new BadRequestException("Loại nội dung không hợp lệ");
    }

    public void moderate(Long user, String type, String id, String decision, String reportId) {
        requireAdmin(user);
        if (!Set.of("APPROVE", "HIDE", "DISMISS").contains(decision))
            throw new BadRequestException("Quyết định không hợp lệ");
        ContentReport report =
                reportId == null ? null : documentStore.findById(reportId, ContentReport.class);
        if (reportId != null
                && (report == null
                        || !type.equals(report.getTargetType())
                        || !id.equals(report.getTargetId())))
            throw new BadRequestException("Báo cáo không khớp nội dung");
        if (!decision.equals("DISMISS")) {
            ModerationStatus status =
                    decision.equals("HIDE") ? ModerationStatus.HIDDEN : ModerationStatus.APPROVED;
            switch (type) {
                case "POST" -> {
                    Post current = documentStore.findById(id, Post.class);
                    if (current == null) throw new ResourceNotFoundException("Không tìm thấy nội dung");
                    if (status == ModerationStatus.APPROVED && postMediaAssets != null) {
                        postMediaAssets.assertCanApprove(current);
                    }
                    var updated = documentStore.updateFirst(
                            Query.query(new Criteria().andOperator(
                                    Criteria.where("_id").is(id),
                                    Criteria.where("moderationStatus")
                                            .is(current.getModerationStatus()))),
                            new Update().set("moderationStatus", status), Post.class);
                    if (updated.getMatchedCount() == 0) {
                        throw new BadRequestException(
                                "Trạng thái bài viết vừa thay đổi; vui lòng tải lại trước khi duyệt");
                    }
                    if (status == ModerationStatus.HIDDEN
                            && current.getModerationStatus() != ModerationStatus.HIDDEN) {
                        try {
                            notificationService.notifyPostModerationResult(
                                    Long.valueOf(current.getUserId()), current.getId(), ModerationStatus.HIDDEN);
                        } catch (RuntimeException notificationFailure) {
                            log.warn("Could not queue admin moderation notice (postId={}, cause={})",
                                    id, notificationFailure.getClass().getSimpleName());
                        }
                    }
                    if (status == ModerationStatus.HIDDEN && postMediaAssets != null) {
                        TransactionCommitHooks.afterCommit(() -> {
                            try {
                                postMediaAssets.queueHiddenPostMedia(current);
                            } catch (RuntimeException cleanupFailure) {
                                log.warn("Admin-hidden post media cleanup deferred (postId={}, cause={})",
                                        id, cleanupFailure.getClass().getSimpleName());
                            }
                        });
                    }
                }
                case "COMMENT" -> reviewComment(id, status, decision);
                case "USER" -> accountModeration.setHidden(user, Long.valueOf(id), decision.equals("HIDE"));
                default -> throw new BadRequestException("Loại nội dung không hợp lệ");
            }
        }
        if (report == null) {
            report = new ContentReport();
            report.setId("moderation:" + user + ":" + type + ":" + id);
            report.setReporterId(user);
            report.setTargetType(type);
            report.setTargetId(id);
            report.setReason("Kiểm duyệt chủ động");
        }
        report.setStatus("RESOLVED");
        report.setReviewedBy(user);
        report.setReviewedAt(Instant.now());
        report.setDecision(decision);
        documentStore.save(report);
        if (!decision.equals("DISMISS")) posts.invalidateAllFeedCache();
    }

    private void reviewComment(String commentId, ModerationStatus targetStatus, String decision) {
        Comment current = documentStore.findById(commentId, Comment.class);
        if (current == null) throw new ResourceNotFoundException("Không tìm thấy nội dung");

        // New async comments do not affect public counters until approved. Older documents default
        // moderationCountersApplied=true so an admin approval does not count them twice.
        if ("APPROVE".equals(decision) && !current.isModerationCountersApplied()) {
            boolean notifyOwner = current.isNotifyAfterModeration();
            var result = documentStore.updateFirst(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(commentId),
                            Criteria.where("moderationCountersApplied").is(false))),
                    new Update().set("moderationStatus", targetStatus)
                            .set("moderationCountersApplied", true)
                            .unset("moderationLeaseUntil")
                            .unset("moderationFailureReason")
                            .unset("notifyAfterModeration"), Comment.class);
            if (result.getModifiedCount() == 0) return; // Another admin action already finalized it.
            documentStore.updateFirst(Query.query(Criteria.where("_id").is(current.getPostId())),
                    new Update().inc("commentCount", 1), Post.class);
            if (current.getParentCommentId() != null && !current.getParentCommentId().isBlank()) {
                documentStore.updateFirst(Query.query(Criteria.where("_id").is(current.getParentCommentId())),
                        new Update().inc("replyCount", 1), Comment.class);
                Comment parent = documentStore.findById(current.getParentCommentId(), Comment.class);
                if (notifyOwner && parent != null && !parent.getUserId().equals(current.getUserId())) {
                    com.aloute.common.util.TransactionCommitHooks.afterCommit(() ->
                            notificationService.notify(Long.valueOf(parent.getUserId()),
                                    Long.valueOf(current.getUserId()), "COMMENT", current.getPostId(),
                                    "đã trả lời bình luận của bạn"));
                }
            } else if (notifyOwner) {
                Post post = documentStore.findById(current.getPostId(), Post.class);
                if (post != null) {
                    com.aloute.common.util.TransactionCommitHooks.afterCommit(() ->
                            notificationService.notify(Long.valueOf(post.getUserId()),
                                    Long.valueOf(current.getUserId()), "COMMENT", current.getPostId(),
                                    "đã bình luận về bài viết của bạn"));
                }
            }
            return;
        }
        var update = new Update().set("moderationStatus", targetStatus)
                .unset("moderationLeaseUntil").unset("notifyAfterModeration");
        if (targetStatus == ModerationStatus.APPROVED) update.unset("moderationFailureReason");
        var result = documentStore.updateFirst(Query.query(Criteria.where("_id").is(commentId)), update, Comment.class);
        if (result.getMatchedCount() == 0) throw new ResourceNotFoundException("Không tìm thấy nội dung");
    }

    @Transactional
    public void save(Long user, String postId, boolean saved) {
        savedPosts.save(user, postId, saved);
    }

    public List<PostResponse> saved(Long user, int page) {
        return savedPosts.saved(user, page);
    }

    public void edit(Long user, String id, CreatePostRequest request) {
        postEditing.edit(user, id, new com.aloute.posts.api.PostEditCommand(request.getContent(),
                request.getImageUrl(), request.getVideoUrl(), request.getVisibility().name()));
    }

    /** Legacy response adapter retains the old Java return type and JSON field set. */
    public List<PostRevision> history(Long user, String postId) {
        return postEditing.history(user, postId).stream().map(view -> {
            PostRevision revision = new PostRevision();
            revision.setId(view.id());
            revision.setPostId(view.postId());
            revision.setContent(view.content());
            revision.setImageUrl(view.imageUrl());
            revision.setVideoUrl(view.videoUrl());
            revision.setVisibility(view.visibility());
            revision.setEditorId(view.editorId());
            revision.setCreatedAt(view.createdAt());
            return revision;
        }).toList();
    }

    private void cleanupBlockRelationshipsAndMarkSynced(String id, Long owner, Long target) {
        removeRelationshipEdges(owner, target);
        // Mark only after all SQL edge removals succeed. If this update fails, the scheduled
        // reconciliation is allowed to repeat the idempotent SQL cleanup.
        documentStore.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("relationshipsSynced", true),
                UserBlock.class);
    }

    private void removeRelationshipEdges(Long owner, Long target) {
        closeFriends.findByRequesterIdAndReceiverId(owner, target).ifPresent(closeFriends::delete);
        closeFriends.findByRequesterIdAndReceiverId(target, owner).ifPresent(closeFriends::delete);
        friends.findByFollowerIdAndFollowedId(owner, target).ifPresent(friends::delete);
        friends.findByFollowerIdAndFollowedId(target, owner).ifPresent(friends::delete);
    }

    private void afterCommitSafely(String operation, Runnable action) {
        TransactionCommitHooks.afterCommit(() -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.error("Post-commit social side effect failed (operation={}, cause={})",
                        operation, ex.getClass().getSimpleName(), ex);
            }
        });
    }


}
