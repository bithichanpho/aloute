package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.TimeAgoUtil;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.ModerationResult;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

@Service
public class PostService {

    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final ILikeRepository likeRepository;
    private final ModerationService moderationService;
    private final NotificationService notificationService;
    private final MongoTemplate mongoTemplate;
    private final ICommentRepository commentRepository;
    private final INotificationRepository notificationRepository;
    private final ImageStorageService imageStorageService;

    public PostService(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            ModerationService moderationService,
            NotificationService notificationService,
            MongoTemplate mongoTemplate,
            ICommentRepository commentRepository,
            INotificationRepository notificationRepository,
            ImageStorageService imageStorageService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.moderationService = moderationService;
        this.notificationService = notificationService;
        this.mongoTemplate = mongoTemplate;
        this.commentRepository = commentRepository;
        this.notificationRepository = notificationRepository;
        this.imageStorageService = imageStorageService;
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
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = postRepository.findAllByOrderByCreatedAtDesc(pageable);

        return toResponses(posts, currentUserId);
    }

    /** Feed theo danh sách người đang follow (dùng cho tab "Following"). */
    public List<PostResponse> getFeedByFollowing(Long currentUserId, List<Long> followingIds, int page, int size) {
        if (followingIds == null || followingIds.isEmpty()) {
            return Collections.emptyList();
        }

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<String> ids = followingIds.stream().map(String::valueOf).collect(Collectors.toList());
        List<Post> posts = postRepository.findByUserIdInOrderByCreatedAtDesc(ids, pageable);

        return toResponses(posts, currentUserId);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId) {
        return getPostsByUser(userId, currentUserId, 0, 20);
    }

    /** Profile posts with bounded pagination to avoid loading an unbounded history. */
    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = postRepository.findByUserIdOrderByCreatedAtDesc(String.valueOf(userId), pageable);
        return toResponses(posts, currentUserId);
    }

    /**
     * Tìm bài viết theo nội dung (dùng cho trang /search).
     * Từ khóa được Pattern.quote để ký tự đặc biệt như "#" hay "(" không bị
     * hiểu thành cú pháp regex.
     */
    public List<PostResponse> searchPosts(String keyword, Long currentUserId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }

        Query query = Query.query(Criteria.where("content")
                        .regex(java.util.regex.Pattern.quote(keyword.trim()), "i"))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(Math.max(1, Math.min(30, limit)));

        return toResponses(mongoTemplate.find(query, Post.class), currentUserId);
    }

    /** Tổng số bài viết của một người (dùng cho con số trên trang cá nhân). */
    public long countPostsByUser(Long userId) {
        return postRepository.countByUserId(String.valueOf(userId));
    }

    public void deletePost(String postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết"));

        if (!post.getUserId().equals(String.valueOf(currentUserId))) {
            throw new UnauthorizedException("Bạn không có quyền xóa bài viết này");
        }

        // Xóa kèm dữ liệu phụ thuộc. Nếu chỉ xóa mỗi document post thì like,
        // comment và notification trỏ tới nó sẽ mồ côi: badge thông báo vẫn đếm,
        // và bấm vào thông báo sẽ mở ra một bài viết không còn tồn tại.
        likeRepository.deleteByPostId(postId);
        commentRepository.deleteByPostId(postId);
        postRepository.delete(post);
        notificationRepository.deleteByReferenceId(postId);

        // Ảnh trên Cloudinary cũng nên được dọn, nhưng lỗi phía Cloudinary
        // KHÔNG được phép làm hỏng thao tác xóa bài đã thực hiện xong.
        try {
            imageStorageService.deleteAlouteImageByUrl(post.getImageUrl());
        } catch (Exception ignored) {
        }
    }

    public void adjustLikeCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query, new Update().inc("likeCount", delta), Post.class);
    }

    public void adjustCommentCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query, new Update().inc("commentCount", delta), Post.class);
    }

    /** Lấy 1 bài viết duy nhất (dùng cho trang thread /post/{id}). */
    public PostResponse getPost(String postId, Long currentUserId) {
        Post post = getPostOrThrow(postId);
        return toResponse(post, currentUserId);
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
        dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

        Long postUserId = Long.valueOf(post.getUserId());
        dto.setUserId(postUserId);

        userRepository.findById(postUserId).ifPresent(user -> {
            dto.setUsername(user.getUsername());
            dto.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        });

        if (currentUserId != null) {
            dto.setLikedByCurrentUser(
                    likeRepository.existsByPostIdAndUserId(post.getId(), String.valueOf(currentUserId)));
        }

        return dto;
    }

    /**
     * Chuyển 1 danh sách Post thành PostResponse theo lô (batch), tránh N+1 query:
     * - 1 query duy nhất tới bảng users (Postgres) để lấy tất cả tác giả liên quan.
     * - 1 query duy nhất tới MongoDB để lấy toàn bộ bài đã được currentUser like.
     * Đồng thời lọc bỏ các bài đã bị ẩn (moderation HIDDEN).
     */
    private List<PostResponse> toResponses(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        // Lấy toàn bộ ID tác giả, đồng thời loại bỏ ID trùng
        Set<Long> userIds = posts.stream().map(post -> Long.valueOf(post.getUserId())).collect(Collectors.toSet());

        // Chỉ dùng một query để lấy tất cả tác giả
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<String> likedPostIds = Collections.emptySet();

        if (currentUserId != null) {
            List<String> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());

            // Chỉ dùng một query MongoDB để lấy toàn bộ bài đã like
            likedPostIds = likeRepository.findByPostIdInAndUserId(postIds, String.valueOf(currentUserId)).stream()
                    .map(Like::getPostId).collect(Collectors.toSet());
        }

        final Set<String> finalLikedPostIds = likedPostIds;

        return posts.stream().filter(post -> post.getModerationStatus() != ModerationStatus.HIDDEN).map(post -> {
            PostResponse dto = new PostResponse();

            dto.setId(post.getId());
            dto.setContent(post.getContent());
            dto.setImageUrl(post.getImageUrl());
            dto.setLikeCount(post.getLikeCount());
            dto.setCommentCount(post.getCommentCount());
            dto.setCreatedAt(post.getCreatedAt());
            dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

            Long authorId = Long.valueOf(post.getUserId());
            dto.setUserId(authorId);

            User author = usersById.get(authorId);

            if (author != null) {
                dto.setUsername(author.getUsername());
                dto.setUserAvatarUrl(AvatarUtil.resolve(author.getAvatarUrl()));
            }

            dto.setLikedByCurrentUser(finalLikedPostIds.contains(post.getId()));

            return dto;
        }).collect(Collectors.toList());
    }
}
