package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.dto.response.CommentCursorPage;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ModerationStatus;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IUserRepository;

import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.springframework.data.domain.Sort;
import java.util.stream.Collectors;

@Service
public class CommentService {

    private final ContentAccessService access;

    private final ICommentRepository commentRepository;
    private final PostService postService;
    private final IUserRepository userRepository;
    private final PostgresDocumentStore documentStore;
    private final CommentPersistenceService commentPersistenceService;
    private final AuthRateLimitService rateLimitService;
    private final PostgresTransactionRetryExecutor transactionRetryExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            IUserRepository userRepository,
            PostgresDocumentStore documentStore,
            CommentPersistenceService commentPersistenceService,
            ContentAccessService access,
            ModerationService moderationService,
            AuthRateLimitService rateLimitService,
            CommentModerationCoordinator moderationCoordinator,
            PostgresTransactionRetryExecutor transactionRetryExecutor) {
        this.access = access;

        this.commentRepository = commentRepository;
        this.postService = postService;
        this.userRepository = userRepository;
        this.documentStore = documentStore;
        this.commentPersistenceService = commentPersistenceService;
        this.rateLimitService = rateLimitService;
        this.transactionRetryExecutor = transactionRetryExecutor;
    }

    /** Compatibility constructor retained for focused service tests. */
    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            IUserRepository userRepository,
            PostgresDocumentStore documentStore,
            CommentPersistenceService commentPersistenceService,
            ContentAccessService access,
            ModerationService moderationService,
            AuthRateLimitService rateLimitService,
            CommentModerationCoordinator moderationCoordinator) {
        this(commentRepository, postService, userRepository, documentStore, commentPersistenceService,
                access, moderationService, rateLimitService, moderationCoordinator, null);
    }

    /** Compatibility constructor retained for focused service tests. */
    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            IUserRepository userRepository,
            PostgresDocumentStore documentStore,
            CommentPersistenceService commentPersistenceService,
            ContentAccessService access,
            ModerationService moderationService,
            AuthRateLimitService rateLimitService) {
        this(commentRepository, postService, userRepository, documentStore, commentPersistenceService,
                access, moderationService, rateLimitService, null, null);
    }

    public CommentResponse createComment(
            String postId, Long currentUserId, CreateCommentRequest request) {
        access.requirePost(postId, currentUserId);
        rateLimitService.checkAndRecord(
                "COMMENT",
                currentUserId.toString(),
                40,
                java.time.Duration.ofMinutes(10),
                java.time.Duration.ofMinutes(10),
                "Bạn bình luận quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa bình luận do gửi quá nhiều yêu cầu.");

        // Comments publish immediately. Community managers approve community posts separately;
        // reported comments are handled after publication instead of entering a global queue.
        var status = com.aloute.model.document.ModerationStatus.APPROVED;
        Comment saved = commentPersistenceService.create(
                postId, currentUserId, request, status, request.getParentCommentId());

        return toResponses(List.of(saved)).stream()
                .findFirst()
                .orElseGet(
                        () -> {
                            CommentResponse dto = new CommentResponse();
                            dto.setId(saved.getId());
                            dto.setContent("Không thể hiển thị bình luận");
                            return dto;
                        });
    }

    public List<CommentResponse> getComments(String postId) {
        return getComments(postId, 0, 50);
    }

    public List<CommentResponse> getComments(String postId, int page, int size) {
        access.requirePost(
                postId,
                com.aloute.security.SecurityUtils.currentUserId(
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication()));
        Long viewer = com.aloute.security.SecurityUtils.currentUserId(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        int pageSize = Math.max(1, Math.min(50, size));
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("postId").is(postId),
                        Criteria.where("parentCommentId").is(null),
                        visibleCommentCriteria(viewer)))
                .with(org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), pageSize,
                        Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id"))));
        return toResponses(documentStore.find(query, Comment.class));
    }

    /**
     * Returns direct replies only. A keyset cursor is used so new replies do not shift existing
     * pages and the browser can request more replies without loading a whole thread.
     */
    public CommentCursorPage getReplies(
            String commentId, Long viewerId, String encodedCursor, int requestedSize) {
        Comment parent = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
        access.requirePost(parent.getPostId(), viewerId);
        if (!isCommentVisibleToViewer(parent, viewerId)) {
            throw new ResourceNotFoundException("Không tìm thấy bình luận");
        }

        int size = Math.max(1, Math.min(50, requestedSize));
        ReplyCursor cursor = decodeReplyCursor(encodedCursor);
        Criteria criteria = new Criteria().andOperator(
                Criteria.where("parentCommentId").is(parent.getId()),
                visibleCommentCriteria(viewerId));
        if (cursor != null) {
            criteria = new Criteria().andOperator(
                    criteria,
                    new Criteria().orOperator(
                            Criteria.where("createdAt").gt(cursor.createdAt()),
                            new Criteria().andOperator(
                                    Criteria.where("createdAt").is(cursor.createdAt()),
                                    Criteria.where("_id").gt(cursor.id()))));
        }
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                .limit(size + 1);
        List<Comment> fetched = documentStore.find(query, Comment.class);
        boolean hasMore = fetched.size() > size;
        List<Comment> consumed = fetched.subList(0, Math.min(size, fetched.size()));
        String nextCursor = consumed.isEmpty() ? "" : encodeReplyCursor(consumed.get(consumed.size() - 1));
        List<CommentResponse> visible = toResponses(consumed);
        return new CommentCursorPage(visible, hasMore ? nextCursor : "", hasMore);
    }

    private Criteria visibleCommentCriteria(Long viewerId) {
        Criteria visibleStatus = new Criteria().orOperator(
                Criteria.where("moderationStatus").is(com.aloute.model.document.ModerationStatus.APPROVED),
                Criteria.where("moderationStatus").is(null),
                Criteria.where("userId").is(viewerId == null ? "__anonymous__" : String.valueOf(viewerId)));
        return new Criteria().andOperator(
                Criteria.where("moderationStatus").ne(com.aloute.model.document.ModerationStatus.HIDDEN),
                visibleStatus);
    }

    private boolean isCommentVisibleToViewer(Comment comment, Long viewerId) {
        if (comment == null || comment.getModerationStatus() == com.aloute.model.document.ModerationStatus.HIDDEN) {
            return false;
        }
        return comment.getModerationStatus() == null
                || comment.getModerationStatus() == com.aloute.model.document.ModerationStatus.APPROVED
                || (viewerId != null && String.valueOf(viewerId).equals(comment.getUserId()));
    }

    private String encodeReplyCursor(Comment comment) {
        String raw = comment.getCreatedAt().toString() + "|" + comment.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ReplyCursor decodeReplyCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int divider = raw.indexOf('|');
            if (divider <= 0 || divider == raw.length() - 1) throw new IllegalArgumentException();
            LocalDateTime createdAt = LocalDateTime.parse(raw.substring(0, divider));
            String id = raw.substring(divider + 1);
            if (id.length() > 100) throw new IllegalArgumentException();
            return new ReplyCursor(createdAt, id);
        } catch (RuntimeException invalid) {
            throw new com.aloute.common.exception.BadRequestException("Cursor phản hồi không hợp lệ");
        }
    }

    private record ReplyCursor(LocalDateTime createdAt, String id) {}

    private void adjustReplyCount(String parentId, int delta) {
        documentStore.updateFirst(
                Query.query(Criteria.where("_id").is(parentId)),
                new Update().inc("replyCount", delta),
                Comment.class);
    }

    private List<CommentResponse> toResponses(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) return List.of();

        var parentIds =
                comments.stream()
                        .map(Comment::getParentCommentId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
        var parents = new java.util.HashMap<String, Comment>();
        if (!parentIds.isEmpty()) {
            commentRepository.findAllById(parentIds).forEach(p -> parents.put(p.getId(), p));
        }

        var userIds = new java.util.HashSet<Long>();
        comments.forEach(c -> userIds.add(Long.valueOf(c.getUserId())));
        parents.values().forEach(c -> userIds.add(Long.valueOf(c.getUserId())));
        var users =
                userRepository.findAllById(userIds).stream()
                        .collect(
                                Collectors.toMap(
                                        com.aloute.model.entity.User::getId,
                                        java.util.function.Function.identity()));

        Long viewer =
                com.aloute.security.SecurityUtils.currentUserId(
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication());
        var visibleAuthors = access.visibleUserIds(viewer, users.values());
        return comments.stream()
                .filter(c -> isCommentVisibleToViewer(c, viewer)
                        && visibleAuthors.contains(Long.valueOf(c.getUserId())))
                .map(c -> toResponse(c, users, parents))
                .collect(Collectors.toList());
    }

    public void deleteComment(String commentId, Long currentUserId) {
        deleteComment(commentId, currentUserId, false);
    }

    public void deleteCommentAsModerator(String commentId, Long currentUserId) {
        deleteComment(commentId, currentUserId, true);
    }

    public Comment getCommentForCommunityManagement(String commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
    }

    private void deleteComment(String commentId, Long currentUserId, boolean moderator) {
        Runnable operation = () -> deleteCommentInTransaction(commentId, currentUserId, moderator);
        if (transactionRetryExecutor == null) operation.run();
        else transactionRetryExecutor.execute(() -> { operation.run(); return null; });
    }

    private void deleteCommentInTransaction(
            String commentId, Long currentUserId, boolean moderator) {
        Comment comment =
                commentRepository
                        .findById(commentId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy bình luận"));

        if (!moderator && !comment.getUserId().equals(String.valueOf(currentUserId))) {
            throw new ForbiddenException("Bạn không có quyền xóa bình luận này");
        }

        if (commentRepository.countByParentCommentId(commentId) > 0) {
            documentStore.updateFirst(
                    Query.query(Criteria.where("_id").is(commentId)),
                    new Update().set("content", "Bình luận đã bị xóa"),
                    Comment.class);
            return;
        }
        long deleted =
                documentStore
                        .remove(Query.query(Criteria.where("_id").is(commentId)), Comment.class)
                        .getDeletedCount();
        if (deleted == 0) return;
        documentStore.remove(Query.query(Criteria.where("commentId").is(commentId)),
                com.aloute.model.document.CommentReaction.class);
        if (comment.isModerationCountersApplied()) {
            if (comment.getParentCommentId() != null)
                adjustReplyCount(comment.getParentCommentId(), -1);
            postService.adjustCommentCount(comment.getPostId(), -1);
        }
    }

    public CommentResponse editComment(String commentId, Long currentUserId, String content) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
        if (!comment.getUserId().equals(currentUserId.toString())) {
            throw new ForbiddenException("Chỉ tác giả được sửa bình luận");
        }
        if (comment.getModerationStatus() == ModerationStatus.HIDDEN) {
            throw new ForbiddenException("Nội dung đã bị ẩn, không thể chỉnh sửa");
        }
        String normalizedContent = content == null ? "" : content.trim();
        comment.setContent(normalizedContent);
        comment.setModerationStatus(ModerationStatus.APPROVED);
        return toResponses(List.of(commentRepository.save(comment))).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Bình luận đang bị ẩn"));
    }

    public java.util.Map<String, Object> react(
            String commentId, Long currentUserId, String type) {
        if (transactionRetryExecutor == null) {
            return reactInTransaction(commentId, currentUserId, type);
        }
        return transactionRetryExecutor.execute(() -> reactInTransaction(commentId, currentUserId, type));
    }

    private java.util.Map<String, Object> reactInTransaction(
            String commentId, Long currentUserId, String type) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
        access.requirePost(comment.getPostId(), currentUserId);
        if (!isCommentVisibleToViewer(comment, currentUserId)) {
            throw new ResourceNotFoundException("Không tìm thấy bình luận");
        }
        String id = commentId + ":" + currentUserId;
        if (type == null || type.isBlank()) {
            documentStore.remove(Query.query(Criteria.where("_id").is(id)),
                    com.aloute.model.document.CommentReaction.class);
        } else {
            com.aloute.model.document.CommentReaction reaction =
                    new com.aloute.model.document.CommentReaction();
            reaction.setId(id);
            reaction.setCommentId(commentId);
            reaction.setUserId(currentUserId);
            reaction.setType(type.toUpperCase());
            documentStore.save(reaction);
        }
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        documentStore.countGroupedBy(
                Query.query(Criteria.where("commentId").is(commentId)),
                com.aloute.model.document.CommentReaction.class, "type")
                .forEach((key, count) -> {
                    if (!key.isEmpty() && key.get(0) != null) counts.put(key.get(0), count);
                });
        return java.util.Map.of("reaction", type == null ? "" : type.toUpperCase(), "counts", counts);
    }

    private CommentResponse toResponse(
            Comment comment,
            java.util.Map<Long, com.aloute.model.entity.User> users,
            java.util.Map<String, Comment> parents) {
        CommentResponse dto = new CommentResponse();
        dto.setId(comment.getId());
        dto.setPostId(comment.getPostId());
        dto.setContent(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setParentCommentId(comment.getParentCommentId());
        dto.setReplyCount(comment.getReplyCount());
        dto.setModerationStatus(comment.getModerationStatus() == null
                ? com.aloute.model.document.ModerationStatus.APPROVED : comment.getModerationStatus());

        Long userId = Long.valueOf(comment.getUserId());
        dto.setUserId(userId);
        var user = users.get(userId);
        if (user != null) {
            dto.setUsername(user.getUsername());
            dto.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        }

        var parent = parents.get(comment.getParentCommentId());
        if (parent != null) {
            var author = users.get(Long.valueOf(parent.getUserId()));
            if (author != null) dto.setParentUsername(author.getUsername());
        }
        return dto;
    }
}
