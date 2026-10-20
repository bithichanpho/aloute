package com.aloute.service;

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
import com.aloute.model.document.SavedPost;
import com.aloute.model.document.UserBlock;
import com.aloute.model.entity.Friend;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;


import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SocialFeatureService {
    private static final Logger log = LoggerFactory.getLogger(SocialFeatureService.class);
    private final MongoTemplate mongo;
    private final ContentAccessService access;
    private final IUserRepository users;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;
    private final PostService posts;
    private final AuthRateLimitService limits;
    private final NotificationService notificationService;
    private final RefreshTokenService refreshTokens;
    private final AuthenticationUserCache authenticationUserCache;
    private final MediaAssetLifecycleService postMediaAssets;

    @org.springframework.beans.factory.annotation.Autowired
    public SocialFeatureService(
            MongoTemplate mongo,
            ContentAccessService access,
            IUserRepository users,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            PostService posts,
            ModerationService moderation,
            AuthRateLimitService limits,
            NotificationService notificationService,
            PostModerationCoordinator moderationCoordinator,
            RefreshTokenService refreshTokens,
            AuthenticationUserCache authenticationUserCache,
            MediaAssetLifecycleService postMediaAssets) {
        this.mongo = mongo;
        this.access = access;
        this.users = users;
        this.friends = friends;
        this.closeFriends = closeFriends;
        this.posts = posts;
        this.limits = limits;
        this.notificationService = notificationService;
        this.refreshTokens = refreshTokens;
        this.authenticationUserCache = authenticationUserCache;
        this.postMediaAssets = postMediaAssets;
    }

    /** Compatibility constructor retained for focused service tests. */
    public SocialFeatureService(
            MongoTemplate mongo, ContentAccessService access, IUserRepository users,
            IFriendRepository friends, ICloseFriendRepository closeFriends, PostService posts,
            ModerationService moderation, AuthRateLimitService limits, NotificationService notificationService) {
        this(mongo, access, users, friends, closeFriends, posts, moderation, limits,
                notificationService, null, null, null, null);
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void block(Long owner, Long target, boolean blocked) {
        if (owner.equals(target)) throw new BadRequestException("Không thể tự chặn chính mình");
        if (!users.existsById(target))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
        String id = owner + ":" + target;
        if (!blocked) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), UserBlock.class);
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
        mongo.save(block);
        mongo.remove(
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("followerId").is(owner).and("targetId").is(target),
                                        Criteria.where("followerId").is(target).and("targetId").is(owner))),
                FollowRequest.class);

        // Mongo stores the block as the security source of truth. Remove PostgreSQL relationship
        // rows only after the block transaction commits. A scheduled reconciliation repairs this
        // cross-store cleanup if SQL becomes temporarily unavailable.
        afterCommitSafely("block relationship cleanup", () -> cleanupBlockRelationshipsAndMarkSynced(id, owner, target));
        posts.invalidateViewerFeedCache(owner);
        posts.invalidateViewerFeedCache(target);
    }

    public List<UserBlock> blocks(Long user) {
        return mongo.find(
                Query.query(Criteria.where("ownerId").is(user)).limit(200), UserBlock.class);
    }

    public SocialCursorPage<UserBlock> blocks(Long user, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        SocialCursor point = decodeSocialCursor(cursor);
        Query query = Query.query(Criteria.where("ownerId").is(user));
        if (point != null) query.addCriteria(new Criteria().orOperator(
                Criteria.where("createdAt").lt(point.createdAt()),
                new Criteria().andOperator(
                        Criteria.where("createdAt").is(point.createdAt()),
                        Criteria.where("_id").lt(point.id()))));
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<UserBlock> items = new java.util.ArrayList<>(mongo.find(query, UserBlock.class));
        boolean hasMore = items.size() > size;
        if (hasMore) items.subList(size, items.size()).clear();
        String next = hasMore && !items.isEmpty()
                ? encodeSocialCursor(items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getId())
                : "";
        return new SocialCursorPage<>(items, next, hasMore);
    }

    public List<FollowRequest> requests(Long user) {
        return mongo.find(
                Query.query(Criteria.where("targetId").is(user)).limit(200), FollowRequest.class);
    }

    public SocialCursorPage<FollowRequest> requests(
            Long user, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        SocialCursor point = decodeSocialCursor(cursor);
        Query query = Query.query(Criteria.where("targetId").is(user));
        if (point != null) query.addCriteria(new Criteria().orOperator(
                Criteria.where("createdAt").lt(point.createdAt()),
                new Criteria().andOperator(
                        Criteria.where("createdAt").is(point.createdAt()),
                        Criteria.where("_id").lt(point.id()))));
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<FollowRequest> items = new java.util.ArrayList<>(mongo.find(query, FollowRequest.class));
        boolean hasMore = items.size() > size;
        if (hasMore) items.subList(size, items.size()).clear();
        String next = hasMore && !items.isEmpty()
                ? encodeSocialCursor(items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getId())
                : "";
        return new SocialCursorPage<>(items, next, hasMore);
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
        FollowRequest request = mongo.findById(id, FollowRequest.class);
        if (request == null || !user.equals(request.getTargetId()))
            throw new ResourceNotFoundException("Không tìm thấy yêu cầu");
        access.requireProfile(user, request.getFollowerId());
        if (!accept) {
            // Declining only changes MongoDB. Delete directly rather than registering a Mongo
            // side effect after a PostgreSQL transaction that has no SQL write to commit.
            mongo.remove(Query.query(Criteria.where("_id").is(request.getId())), FollowRequest.class);
            return;
        }
        if (!friends.existsByFollowerIdAndFollowedId(request.getFollowerId(), user)) {
            friends.save(new Friend(request.getFollowerId(), user));
        }
        // If this Mongo cleanup fails after the SQL commit, reconciliation observes the persisted
        // Friend edge and safely removes the stale FollowRequest later.
        afterCommitSafely("follow-request cleanup", () ->
                mongo.remove(Query.query(Criteria.where("_id").is(request.getId())), FollowRequest.class));
        posts.invalidateViewerFeedCache(user);
        posts.invalidateViewerFeedCache(request.getFollowerId());
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
                Comment comment = mongo.findById(id, Comment.class);
                if (comment == null)
                    throw new ResourceNotFoundException("Không tìm thấy bình luận");
                access.requirePost(comment.getPostId(), user);
            }
            case "USER" -> access.requireProfile(user, Long.valueOf(id));
            default -> throw new BadRequestException("Loại báo cáo không hợp lệ");
        }
        ContentReport report = new ContentReport();
        report.setId(user + ":" + type + ":" + id);
        ContentReport existing = mongo.findById(report.getId(), ContentReport.class);
        if (existing != null) return existing;
        report.setReporterId(user);
        report.setTargetType(type);
        report.setTargetId(id);
        report.setReason(reason.trim());
        return mongo.insert(report);
    }

    public void requireAdmin(Long user) {
        if (users.findById(user)
                .filter(
                        u ->
                                u.isActive()
                                        && u.getRole() != null
                                        && "ROLE_ADMIN".equals(u.getRole().getName()))
                .isEmpty())
            throw new ForbiddenException("Chỉ quản trị viên xử lý báo cáo được truy cập");
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
        return Map.of(
                "reports", reports.items(),
                "posts", List.of(),
                "comments", List.of(),
                "page", page,
                "size", size,
                "hasMore", reports.hasMore());
    }

    private <T> QueueSlice<T> queueSlice(Query base, Class<T> type, Sort sort, long offset, int size) {
        base.with(sort).skip(offset).limit(size + 1);
        List<T> found = mongo.find(base, type);
        boolean hasMore = found.size() > size;
        if (hasMore) found = found.subList(0, size);
        return new QueueSlice<>(List.copyOf(found), hasMore);
    }

    private record QueueSlice<T>(List<T> items, boolean hasMore) {}

    public Object reviewContent(Long user, String type, String id) {
        requireAdmin(user);
        if (type.equals("POST")) return mongo.findById(id, Post.class);
        if (type.equals("COMMENT")) return mongo.findById(id, Comment.class);
        if (type.equals("USER"))
            return users.findById(Long.valueOf(id))
                    .map(
                            u ->
                                    Map.of(
                                            "username",
                                            u.getUsername(),
                                            "fullName",
                                            u.getFullName(),
                                            "active",
                                            u.isActive()))
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản"));
        throw new BadRequestException("Loại nội dung không hợp lệ");
    }

    public void moderate(Long user, String type, String id, String decision, String reportId) {
        requireAdmin(user);
        if (!Set.of("APPROVE", "HIDE", "DISMISS").contains(decision))
            throw new BadRequestException("Quyết định không hợp lệ");
        ContentReport report =
                reportId == null ? null : mongo.findById(reportId, ContentReport.class);
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
                    Post current = mongo.findById(id, Post.class);
                    if (current == null) throw new ResourceNotFoundException("Không tìm thấy nội dung");
                    if (status == ModerationStatus.APPROVED && postMediaAssets != null) {
                        postMediaAssets.assertCanApprove(current);
                    }
                    var updated = mongo.updateFirst(
                            Query.query(new Criteria().andOperator(
                                    Criteria.where("_id").is(id),
                                    Criteria.where("moderationStatus")
                                            .is(current.getModerationStatus()))),
                            new Update().set("moderationStatus", status), Post.class);
                    if (updated.getMatchedCount() == 0) {
                        throw new BadRequestException(
                                "Trạng thái bài viết vừa thay đổi; vui lòng tải lại trước khi duyệt");
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
                case "USER" -> {
                    var target =
                            users.findById(Long.valueOf(id))
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Không tìm thấy tài khoản"));
                    if (target.getId().equals(user)
                            || Set.of("ROLE_ADMIN", "ROLE_HOST")
                                    .contains(target.getRole().getName())) {
                        throw new BadRequestException("Không thể khóa tài khoản quản trị được bảo vệ");
                    }
                    target.setActive(!decision.equals("HIDE"));
                    target.setSelfDeactivated(false);
                    target.setTokensRevokedAt(Instant.now());
                    users.save(target);
                    if (refreshTokens != null) refreshTokens.revokeAll(target.getId(), target.getTokensRevokedAt());
                    if (authenticationUserCache != null) {
                        String targetUsername = target.getUsername();
                        com.aloute.common.util.TransactionCommitHooks.afterCommit(
                                () -> authenticationUserCache.invalidate(targetUsername));
                    }
                }
                default -> throw new BadRequestException("Loại nội dung không hợp lệ");
            }
        }
        if (report == null) {
            report = new ContentReport();
            report.setReporterId(user);
            report.setTargetType(type);
            report.setTargetId(id);
            report.setReason("Kiểm duyệt chủ động");
        }
        report.setStatus("RESOLVED");
        report.setReviewedBy(user);
        report.setReviewedAt(Instant.now());
        report.setDecision(decision);
        mongo.save(report);
        if (!decision.equals("DISMISS")) posts.invalidateAllFeedCache();
    }

    private void reviewComment(String commentId, ModerationStatus targetStatus, String decision) {
        Comment current = mongo.findById(commentId, Comment.class);
        if (current == null) throw new ResourceNotFoundException("Không tìm thấy nội dung");

        // New async comments do not affect public counters until approved. Older documents default
        // moderationCountersApplied=true so an admin approval does not count them twice.
        if ("APPROVE".equals(decision) && !current.isModerationCountersApplied()) {
            boolean notifyOwner = current.isNotifyAfterModeration();
            var result = mongo.updateFirst(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(commentId),
                            Criteria.where("moderationCountersApplied").is(false))),
                    new Update().set("moderationStatus", targetStatus)
                            .set("moderationCountersApplied", true)
                            .unset("moderationLeaseUntil")
                            .unset("moderationFailureReason")
                            .unset("notifyAfterModeration"), Comment.class);
            if (result.getModifiedCount() == 0) return; // Another admin action already finalized it.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(current.getPostId())),
                    new Update().inc("commentCount", 1), Post.class);
            if (current.getParentCommentId() != null && !current.getParentCommentId().isBlank()) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(current.getParentCommentId())),
                        new Update().inc("replyCount", 1), Comment.class);
                Comment parent = mongo.findById(current.getParentCommentId(), Comment.class);
                if (notifyOwner && parent != null && !parent.getUserId().equals(current.getUserId())) {
                    com.aloute.common.util.TransactionCommitHooks.afterCommit(() ->
                            notificationService.notify(Long.valueOf(parent.getUserId()),
                                    Long.valueOf(current.getUserId()), "COMMENT", current.getPostId(),
                                    "đã trả lời bình luận của bạn"));
                }
            } else if (notifyOwner) {
                Post post = mongo.findById(current.getPostId(), Post.class);
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
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(commentId)), update, Comment.class);
        if (result.getMatchedCount() == 0) throw new ResourceNotFoundException("Không tìm thấy nội dung");
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void save(Long user, String postId, boolean saved) {
        String id = user + ":" + postId;
        if (!saved) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), SavedPost.class);
            return;
        }
        access.requirePost(postId, user);
        SavedPost item = new SavedPost();
        item.setId(id);
        item.setUserId(user);
        item.setPostId(postId);
        mongo.save(item);
    }

    public List<PostResponse> saved(Long user, int page) {
        List<SavedPost> items =
                mongo.find(
                        Query.query(Criteria.where("userId").is(user))
                                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                                .skip(Math.max(0, page) * 20L)
                                .limit(20),
                        SavedPost.class);
        return posts.getPostsByIds(items.stream().map(SavedPost::getPostId).toList(), user);
    }

    public void edit(Long user, String id, CreatePostRequest request) {
        limits.checkAndRecord("POST_EDIT", String.valueOf(user), 20,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofHours(1),
                "Bạn đã chỉnh sửa quá nhiều lần. Thử lại sau %d phút.",
                "Tạm khóa chỉnh sửa bài viết một giờ do vượt giới hạn.");
        Post post = access.requirePost(id, user);
        if (!post.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được sửa bài");
        if (post.getModerationStatus() == ModerationStatus.HIDDEN)
            throw new ForbiddenException("Nội dung đã bị ẩn, không thể chỉnh sửa");
        if (java.util.Objects.equals(post.getContent(), request.getContent())
                && java.util.Objects.equals(post.getImageUrl(), request.getImageUrl())
                && java.util.Objects.equals(post.getVideoUrl(), request.getVideoUrl())
                && java.util.Objects.equals(post.getVisibility(), request.getVisibility())) return;
        PostRevision revision = new PostRevision();
        revision.setPostId(id);
        revision.setContent(post.getContent());
        revision.setImageUrl(post.getImageUrl());
        revision.setVideoUrl(post.getVideoUrl());
        revision.setEditorId(user);
        revision.setVisibility(
                post.getVisibility() == null ? "PUBLIC" : post.getVisibility().name());
        if (postMediaAssets != null) {
            postMediaAssets.validatePostMediaUrl(request.getImageUrl(), post.getImageUrl(), post.getUserId());
            postMediaAssets.validatePostMediaUrl(request.getVideoUrl(), post.getVideoUrl(), post.getUserId());
        }
        ModerationStatus targetStatus = ModerationStatus.APPROVED;
        var update = new Update()
                .set("content", request.getContent())
                .set("imageUrl", request.getImageUrl())
                .set("videoUrl", request.getVideoUrl())
                .set("visibility", request.getVisibility())
                .set("hashtags", ContentTagService.extract(request.getContent(), '#'))
                .set("mentions", ContentTagService.extract(request.getContent(), '@'))
                .set("moderationStatus", targetStatus)
                .set("toxicityScore", 0.0)
                .set("moderationAttempts", 0)
                .unset("moderationLastAttemptAt")
                .unset("moderationFailureReason")
                .unset("moderationLeaseUntil")
                .set("notifyMentionsAfterModeration", false)
                .push("editHistory").slice(-100).each(revision)
                .set("updatedAt", java.time.LocalDateTime.now());
        var updated = mongo.updateFirst(
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
                postMediaAssets.attachPostMedia(request.getImageUrl(), post.getId(), post.getUserId());
                if (request.getVideoUrl() == null || !request.getVideoUrl().equals(request.getImageUrl())) {
                    postMediaAssets.attachPostMedia(request.getVideoUrl(), post.getId(), post.getUserId());
                }
            } catch (RuntimeException trackingFailure) {
                log.warn("Post edit media attachment tracking deferred (postId={}, cause={})",
                        id, trackingFailure.getClass().getSimpleName());
            }
        }
        posts.invalidateViewerFeedCache(user);
    }

    public List<PostRevision> history(Long user, String post) {
        Post target = access.requirePost(post, user);
        if (!target.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được xem lịch sử");
        if (target.getEditHistory() == null) return List.of();
        return target.getEditHistory().stream()
                .sorted(Comparator.comparing(PostRevision::getCreatedAt).reversed())
                .limit(100)
                .toList();
    }


    private void cleanupBlockRelationshipsAndMarkSynced(String id, Long owner, Long target) {
        removeRelationshipEdges(owner, target);
        // Mark only after all SQL edge removals succeed. If this update fails, the scheduled
        // reconciliation is allowed to repeat the idempotent SQL cleanup.
        mongo.updateFirst(
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
