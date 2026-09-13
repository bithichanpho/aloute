package com.aloute.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.Post;
import com.aloute.repository.ICommentRepository;

@Service
public class CommentService {

    private final ICommentRepository commentRepository;
    private final PostService postService;
    private final UserService userService;
    private final NotificationService notificationService;

    public CommentService(
            ICommentRepository commentRepository,
            PostService postService,
            UserService userService,
            NotificationService notificationService) {
        this.commentRepository = commentRepository;
        this.postService = postService;
        this.userService = userService;
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
            parent.setReplyCount(parent.getReplyCount() + 1);
            commentRepository.save(parent);

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

        return toResponse(saved);
    }

    public List<CommentResponse> getComments(String postId) {
        return commentRepository.findByPostIdOrderByCreatedAtAsc(postId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public void deleteComment(String commentId, Long currentUserId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bình luận"));

        if (!comment.getUserId().equals(String.valueOf(currentUserId))) {
            throw new UnauthorizedException("Bạn không có quyền xóa bình luận này");
        }
        commentRepository.delete(comment);
        postService.adjustCommentCount(comment.getPostId(), -1);
    }

    private CommentResponse toResponse(Comment comment) {
        CommentResponse dto = new CommentResponse();
        dto.setId(comment.getId());
        dto.setPostId(comment.getPostId());
        dto.setContent(comment.getContent());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setParentCommentId(comment.getParentCommentId());
        dto.setReplyCount(comment.getReplyCount());

        Long userId = Long.valueOf(comment.getUserId());
        dto.setUserId(userId);
        userService.findUserBasicInfo(userId).ifPresent(u -> {
            dto.setUsername(u.getUsername());
            dto.setUserAvatarUrl(u.getAvatarUrl());
        });

        if (comment.getParentCommentId() != null) {
            commentRepository.findById(comment.getParentCommentId()).ifPresent(parent ->
                    userService.findUserBasicInfo(Long.valueOf(parent.getUserId()))
                            .ifPresent(pu -> dto.setParentUsername(pu.getUsername())));
        }

        return dto;
    }
}