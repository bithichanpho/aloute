package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CommentService {

    private final ContentAccessService access;

    private final ICommentRepository commentRepository;
    private final PostService postService;
    private final IUserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final NotificationService notificationService;
    private final ModerationService moderationService;
    private final AuthRateLimitService rateLimitService;

    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            IUserRepository userRepository,
            MongoTemplate mongoTemplate,
            NotificationService notificationService,
            ContentAccessService access,
            ModerationService moderationService,
            AuthRateLimitService rateLimitService) {
        this.access = access;

        this.commentRepository = commentRepository;
        this.postService = postService;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.notificationService = notificationService;
        this.moderationService = moderationService;
        this.rateLimitService = rateLimitService;
    }

    public CommentResponse createComment(
            String postId, Long currentUserId, CreateCommentRequest request) {
        Post post = access.requirePost(postId, currentUserId);
        rateLimitService.checkAndRecord(
                "COMMENT",
                currentUserId.toString(),
                40,
                java.time.Duration.ofMinutes(10),
                java.time.Duration.ofMinutes(10),
                "Bạn bình luận quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa bình luận do gửi quá nhiều yêu cầu.");
        var moderation = moderationService.check(request.getContent());

        Comment parent = null;
        if (request.getParentCommentId() != null && !request.getParentCommentId().isBlank()) {
            parent =
                    commentRepository
                            .findById(request.getParentCommentId())
                            .orElseThrow(
                                    () ->
                                            new ResourceNotFoundException(
                                                    "Không tìm thấy bình luận gốc để trả lời"));
            if (!parent.getPostId().equals(postId)) {
                throw new ResourceNotFoundException("Bình luận không thuộc bài viết này");
            }
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(String.valueOf(currentUserId));
        comment.setContent(request.getContent());
        comment.setModerationStatus(moderationService.resolveStatus(moderation.getScore()));
        comment.setParentCommentId(parent != null ? parent.getId() : null);

        Comment saved = commentRepository.save(comment);
        postService.adjustCommentCount(postId, 1);

        if (parent != null) {
            adjustReplyCount(parent.getId(), 1);

            Long parentAuthorId = Long.valueOf(parent.getUserId());
            if (!parentAuthorId.equals(currentUserId)) {
                notificationService.notify(
                        parentAuthorId,
                        currentUserId,
                        "COMMENT",
                        postId,
                        "đã trả lời bình luận của bạn");
            }
        } else {
            notificationService.notify(
                    Long.valueOf(post.getUserId()),
                    currentUserId,
                    "COMMENT",
                    postId,
                    "đã bình luận về bài viết của bạn");
        }

        return toResponses(List.of(saved)).stream()
                .findFirst()
                .orElseGet(
                        () -> {
                            CommentResponse dto = new CommentResponse();
                            dto.setId(saved.getId());
                            dto.setContent("Bình luận đang được kiểm duyệt");
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
        List<Comment> comments =
                commentRepository.findByPostIdOrderByCreatedAtAsc(
                        postId,
                        org.springframework.data.domain.PageRequest.of(
                                Math.max(0, page), Math.max(1, Math.min(50, size))));
        return toResponses(comments);
    }

    private void adjustReplyCount(String parentId, int delta) {
        mongoTemplate.updateFirst(
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
                .filter(
                        c ->
                                c.getModerationStatus()
                                                != com.aloute.model.document.ModerationStatus.HIDDEN
                                        && visibleAuthors.contains(Long.valueOf(c.getUserId())))
                .map(c -> toResponse(c, users, parents))
                .collect(Collectors.toList());
    }

    public void deleteComment(String commentId, Long currentUserId) {
        Comment comment =
                commentRepository
                        .findById(commentId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy bình luận"));

        if (!comment.getUserId().equals(String.valueOf(currentUserId))) {
            throw new ForbiddenException("Bạn không có quyền xóa bình luận này");
        }

        if (commentRepository.countByParentCommentId(commentId) > 0) {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(commentId)),
                    new Update().set("content", "Bình luận đã bị xóa"),
                    Comment.class);
            return;
        }
        long deleted =
                mongoTemplate
                        .remove(Query.query(Criteria.where("_id").is(commentId)), Comment.class)
                        .getDeletedCount();
        if (deleted == 0) return;
        if (comment.getParentCommentId() != null)
            adjustReplyCount(comment.getParentCommentId(), -1);
        postService.adjustCommentCount(comment.getPostId(), -1);
    }

    public CommentResponse editComment(String commentId, Long currentUserId, String content) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
        if (!comment.getUserId().equals(currentUserId.toString())) {
            throw new ForbiddenException("Chỉ tác giả được sửa bình luận");
        }
        var result = moderationService.check(content);
        comment.setContent(content.trim());
        comment.setModerationStatus(moderationService.resolveStatus(result.getScore()));
        return toResponses(List.of(commentRepository.save(comment))).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Bình luận đang bị ẩn"));
    }

    public java.util.Map<String, Object> react(
            String commentId, Long currentUserId, String type) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));
        access.requirePost(comment.getPostId(), currentUserId);
        String id = commentId + ":" + currentUserId;
        if (type == null || type.isBlank()) {
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)),
                    com.aloute.model.document.CommentReaction.class);
        } else {
            com.aloute.model.document.CommentReaction reaction =
                    new com.aloute.model.document.CommentReaction();
            reaction.setId(id);
            reaction.setCommentId(commentId);
            reaction.setUserId(currentUserId);
            reaction.setType(type.toUpperCase());
            mongoTemplate.save(reaction);
        }
        var rows = mongoTemplate.aggregate(
                org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                        org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                                Criteria.where("commentId").is(commentId)),
                        org.springframework.data.mongodb.core.aggregation.Aggregation.group("type")
                                .count().as("count")),
                com.aloute.model.document.CommentReaction.class,
                org.bson.Document.class).getMappedResults();
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        rows.forEach(row -> counts.put(
                String.valueOf(row.get("_id")), ((Number) row.get("count")).longValue()));
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
