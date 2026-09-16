package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IUserRepository;

@Service
public class CommentService {

    private final ICommentRepository commentRepository;
    private final PostService postService;
    private final IUserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final NotificationService notificationService;

    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            IUserRepository userRepository,
            MongoTemplate mongoTemplate,
            NotificationService notificationService) {
        this.commentRepository = commentRepository;
        this.postService = postService;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.notificationService = notificationService;
    }

    public CommentResponse createComment(String postId, Long currentUserId, CreateCommentRequest request) {
        Post post = postService.getPostOrThrow(postId);

        Comment parent = null;
        if (request.getParentCommentId() != null && !request.getParentCommentId().isBlank()) {
            parent = commentRepository.findById(request.getParentCommentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận gốc để trả lời"));
            if (!parent.getPostId().equals(postId)) {
                throw new ResourceNotFoundException("Bình luận không thuộc bài viết này");
            }
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(String.valueOf(currentUserId));
        comment.setContent(request.getContent());
        comment.setParentCommentId(parent != null ? parent.getId() : null);

        Comment saved = commentRepository.save(comment);
        postService.adjustCommentCount(postId, 1);

        if (parent != null) {
            adjustReplyCount(parent.getId(), 1);

            Long parentAuthorId = Long.valueOf(parent.getUserId());
            if (!parentAuthorId.equals(currentUserId)) {
                notificationService.notify(
                        parentAuthorId, currentUserId,
                        "COMMENT", postId, "đã trả lời bình luận của bạn"
                );
            }
        } else {
            notificationService.notify(
                    Long.valueOf(post.getUserId()), currentUserId,
                    "COMMENT", postId, "đã bình luận về bài viết của bạn"
            );
        }

        return toResponses(List.of(saved)).get(0);
    }

    public List<CommentResponse> getComments(String postId) {
        return getComments(postId, 0, 50);
    }

    public List<CommentResponse> getComments(String postId, int page, int size) {
        List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, Math.min(50, size))));
        return toResponses(comments);
    }

    private void adjustReplyCount(String parentId, int delta) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(parentId)),
                new Update().inc("replyCount", delta), Comment.class);
    }

    private List<CommentResponse> toResponses(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) return List.of();

        var parentIds = comments.stream()
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
        var users = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(
                com.aloute.model.entity.User::getId, java.util.function.Function.identity()));

        return comments.stream().map(c -> toResponse(c, users, parents)).collect(Collectors.toList());
    }

    public void deleteComment(String commentId, Long currentUserId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));

        if (!comment.getUserId().equals(String.valueOf(currentUserId))) {
            throw new UnauthorizedException("Bạn không có quyền xóa bình luận này");
        }
        long deleted = mongoTemplate.remove(Query.query(Criteria.where("_id").is(commentId)), Comment.class).getDeletedCount();
        if (deleted == 0) return;
        if (comment.getParentCommentId() != null) adjustReplyCount(comment.getParentCommentId(), -1);
        postService.adjustCommentCount(comment.getPostId(), -1);
    }

    private CommentResponse toResponse(Comment comment, java.util.Map<Long, com.aloute.model.entity.User> users, java.util.Map<String, Comment> parents) {
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