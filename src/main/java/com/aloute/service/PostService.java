package com.aloute.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.ModerationResult;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

@Service
public class PostService {

    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final ILikeRepository likeRepository;
    private final ModerationService moderationService;
    private final NotificationService notificationService;

    public PostService(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            ModerationService moderationService,
            NotificationService notificationService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.moderationService = moderationService;
        this.notificationService = notificationService;
    }

    public PostResponse createPost(Long currentUserId, CreatePostRequest request) {
        ModerationResult moderation = moderationService.check(request.getContent());
        ModerationStatus status = moderationService.resolveStatus(moderation.getScore());

        Post post = new Post();
        post.setUserId(String.valueOf(currentUserId));
        post.setContent(request.getContent());
        post.setImageUrl(request.getImageUrl());
        post.setModerationStatus(status);
        post.setToxicityScore(moderation.getScore());

        Post saved = postRepository.save(post);
        return toResponse(saved, currentUserId);
    }

    /** Feed đơn giản: toàn bộ bài viết APPROVED, mới nhất trước. */
    public List<PostResponse> getFeed(Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = postRepository.findAllByOrderByCreatedAtDesc(pageable);

        return posts.stream()
                .filter(p -> p.getModerationStatus() != ModerationStatus.HIDDEN)
                .map(p -> toResponse(p, currentUserId))
                .collect(Collectors.toList());
    }

    /** Feed theo danh sách người đang follow (dùng cho tab "Following"). */
    public List<PostResponse> getFeedByFollowing(Long currentUserId, List<Long> followingIds, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<String> ids = followingIds.stream().map(String::valueOf).collect(Collectors.toList());
        List<Post> posts = postRepository.findByUserIdInOrderByCreatedAtDesc(ids, pageable);

        return posts.stream()
                .filter(p -> p.getModerationStatus() != ModerationStatus.HIDDEN)
                .map(p -> toResponse(p, currentUserId))
                .collect(Collectors.toList());
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId) {
        List<Post> posts = postRepository.findByUserIdOrderByCreatedAtDesc(String.valueOf(userId));
        return posts.stream()
                .filter(p -> p.getModerationStatus() != ModerationStatus.HIDDEN)
                .map(p -> toResponse(p, currentUserId))
                .collect(Collectors.toList());
    }

    public void deletePost(String postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết"));

        if (!post.getUserId().equals(String.valueOf(currentUserId))) {
            throw new UnauthorizedException("Bạn không có quyền xóa bài viết này");
        }
        postRepository.delete(post);
    }

    /** Gọi từ LikeService/CommentService để cập nhật số đếm denormalized. */
    public void adjustLikeCount(String postId, int delta) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setLikeCount(Math.max(0, post.getLikeCount() + delta));
            postRepository.save(post);
        });
    }

    public void adjustCommentCount(String postId, int delta) {
        postRepository.findById(postId).ifPresent(post -> {
            post.setCommentCount(Math.max(0, post.getCommentCount() + delta));
            postRepository.save(post);
        });
    }

    public Post getPostOrThrow(String postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết"));
    }

    private PostResponse toResponse(Post post, Long currentUserId) {
        PostResponse dto = new PostResponse();
        dto.setId(post.getId());
        dto.setContent(post.getContent());
        dto.setImageUrl(post.getImageUrl());
        dto.setLikeCount(post.getLikeCount());
        dto.setCommentCount(post.getCommentCount());
        dto.setCreatedAt(post.getCreatedAt());

        Long postUserId = Long.valueOf(post.getUserId());
        dto.setUserId(postUserId);

        userRepository.findById(postUserId).ifPresent(user -> {
            dto.setUsername(user.getUsername());
            dto.setUserAvatarUrl(user.getAvatarUrl());
        });

        if (currentUserId != null) {
            dto.setLikedByCurrentUser(
                    likeRepository.existsByPostIdAndUserId(post.getId(), String.valueOf(currentUserId)));
        }

        return dto;
    }
}
