package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.ReactionType;
import com.aloute.common.util.TimeAgoUtil;
import java.util.Collections;
import java.util.HashMap;
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
import com.aloute.dto.request.RepostRequest;
import com.aloute.dto.response.ModerationResult;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.RepostResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
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
    private final IRepostRepository repostRepository;
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
            IRepostRepository repostRepository,
            ImageStorageService imageStorageService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.moderationService = moderationService;
        this.notificationService = notificationService;
        this.mongoTemplate = mongoTemplate;
        this.commentRepository = commentRepository;
        this.notificationRepository = notificationRepository;
        this.repostRepository = repostRepository;
        this.imageStorageService = imageStorageService;
    }

    public PostResponse createPost(Long currentUserId, CreatePostRequest request) {
        return createPost(currentUserId, request, null);
    }

    /** @param communityId null nếu đăng lên feed cá nhân bình thường; khác null nếu đăng trong 1 hội nhóm. */
    public PostResponse createPost(Long currentUserId, CreatePostRequest request, String communityId) {
        ModerationResult moderation = moderationService.check(request.getContent());
        ModerationStatus status = moderationService.resolveStatus(moderation.getScore());

        Post post = new Post();
        post.setUserId(String.valueOf(currentUserId));
        post.setContent(request.getContent());
        post.setImageUrl(request.getImageUrl());
        post.setModerationStatus(status);
        post.setToxicityScore(moderation.getScore());
        post.setCommunityId(communityId);

        Post saved = postRepository.save(post);
        return toResponse(saved, currentUserId);
    }

    /** Bài viết trên trang cá nhân, KHÔNG kèm những lượt "đăng lại thuần" (không lời bình) —
     *  giống tab "Threads" của Threads: chỉ gồm bài gốc của user + các bài "Quote" (đăng lại kèm
     *  lời bình, vì đó thật sự là nội dung do chính user viết). Đăng lại thuần nằm riêng ở
     *  {@link #getRepostsByUser}. */
    private Query ownTimelineQuery(Long userId, Pageable pageable) {
        Criteria hasOwnCaption = new Criteria().andOperator(
                Criteria.where("content").ne(null), Criteria.where("content").ne(""));
        Criteria criteria = Criteria.where("userId").is(String.valueOf(userId))
                .and("moderationStatus").ne(ModerationStatus.HIDDEN)
                .orOperator(Criteria.where("repostOfId").is(null), hasOwnCaption);
        return new Query(criteria).with(pageable);
    }

    /**
     * Feed "Dành cho bạn": chỉ render POST thực sự. Repost thuần là quan hệ trong collection
     * reposts nên không tạo thêm một card trùng. Nếu một người đang follow repost một bài,
     * bài gốc nhận tín hiệu tăng thứ tự hiển thị.
     */
    public List<PostResponse> getFeed(Long currentUserId, List<Long> followingIds, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(20, size));
        Pageable candidatePage = PageRequest.of(0, Math.min(200, Math.max(80, (safePage + 1) * safeSize * 6)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> candidates = postRepository.findAllForFeed(candidatePage);

        if (followingIds != null && !followingIds.isEmpty() && !candidates.isEmpty()) {
            List<String> friendIds = followingIds.stream().map(String::valueOf).toList();
            List<Repost> friendReposts = repostRepository.findByUserIdIn(friendIds, PageRequest.of(0, 500));
            Map<String, Integer> boost = friendReposts.stream()
                    .map(Repost::getPostId)
                    .collect(Collectors.toMap(Function.identity(), id -> 1, Integer::sum));
            candidates.sort((a, b) -> Long.compare(
                    feedScore(b, boost.getOrDefault(b.getId(), 0)),
                    feedScore(a, boost.getOrDefault(a.getId(), 0))));
        }

        int from = Math.min(safePage * safeSize, candidates.size());
        int to = Math.min(from + safeSize, candidates.size());
        if (from >= to) return Collections.emptyList();
        List<PostResponse> result = toResponses(candidates.subList(from, to), currentUserId);
        attachRecentReposters(result);
        return result;
    }

    /** Feed "Following": không đưa repost thuần thành một item riêng. */
    public List<PostResponse> getFeedByFollowing(Long currentUserId, List<Long> followingIds, int page, int size) {
        if (followingIds == null || followingIds.isEmpty()) return Collections.emptyList();

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<String> ids = followingIds.stream().map(String::valueOf).collect(Collectors.toList());
        List<PostResponse> result = toResponses(
                postRepository.findByUserIdInOrderByCreatedAtDesc(ids, pageable), currentUserId);
        attachRecentReposters(result);
        return result;
    }

    private long feedScore(Post post, int repostSignals) {
        long base = post.getCreatedAt() != null
                ? post.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC) : 0L;
        // Mỗi repost từ người đang follow tạo một tín hiệu vừa phải; không biến bài rất cũ
        // thành bài đứng đầu vô thời hạn.
        return base + repostSignals * 45L * 60L;
    }

    /** Feed của 1 hội nhóm cụ thể (dùng cho trang chi tiết hội nhóm). */
    public List<PostResponse> getFeedByCommunity(String communityId, Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = postRepository.findByCommunityIdOrderByCreatedAtDesc(communityId, pageable);
        return toResponses(posts, currentUserId);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId) {
        return getPostsByUser(userId, currentUserId, 0, 20);
    }

    /**
     * Profile posts with bounded pagination to avoid loading an unbounded history.
     * Giống tab "Threads" của Threads: KHÔNG gồm các lượt "đăng lại thuần" (không lời bình) —
     * những bài đó nằm ở tab riêng, xem {@link #getRepostsByUser}.
     */
    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = mongoTemplate.find(ownTimelineQuery(userId, pageable), Post.class);
        return toResponses(posts, currentUserId);
    }

    /**
     * Tab "Đăng lại" trên trang cá nhân (giống Threads): CHỈ những bài user đã đăng lại
     * MÀ KHÔNG kèm lời bình. Bài "Quote" (đăng lại kèm lời bình) đã nằm sẵn trong dòng thời
     * gian chính ({@link #getPostsByUser}) nên không lặp lại ở đây.
     */
    public List<PostResponse> getRepostsByUser(Long userId, Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(20, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Repost> reposts = repostRepository.findByUserIdOrderByCreatedAtDesc(String.valueOf(userId), pageable);
        if (reposts.isEmpty()) return Collections.emptyList();

        List<String> ids = reposts.stream().map(Repost::getPostId).toList();
        Map<String, Post> originals = postRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<PostResponse> result = new java.util.ArrayList<>();
        for (Repost repost : reposts) {
            Post original = originals.get(repost.getPostId());
            if (original == null || original.getModerationStatus() == ModerationStatus.HIDDEN) continue;
            PostResponse response = toResponse(original, currentUserId);
            response.setRepostedByCurrentUser(repostRepository
                    .findByUserIdAndPostId(String.valueOf(currentUserId), original.getId()).isPresent());
            result.add(response);
        }
        return result;
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

    /**
     * Tổng số nội dung do user thực sự đăng, dùng cho con số "bài viết" trên profile.
     * Repost thuần chỉ là quan hệ chia sẻ nên KHÔNG tính vào số bài viết; Quote có lời bình
     * vẫn được tính vì đó là nội dung do chính user tạo.
     */
    public long countPostsByUser(Long userId) {
        Criteria hasOwnCaption = new Criteria().andOperator(
                Criteria.where("content").ne(null), Criteria.where("content").ne(""));
        Criteria criteria = Criteria.where("userId").is(String.valueOf(userId))
                .and("moderationStatus").ne(ModerationStatus.HIDDEN)
                .orOperator(Criteria.where("repostOfId").is(null), hasOwnCaption);
        return mongoTemplate.count(new Query(criteria), Post.class);
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

        // Xóa quan hệ repost thuần trỏ tới bài đang bị xóa (nếu đây là bài gốc).
        // Với dữ liệu cũ có repostOfId trên chính Post, đồng bộ lại bộ đếm bài gốc
        // thay vì gọi một helper không còn tồn tại.
        repostRepository.deleteByPostId(postId);
        if (post.getRepostOfId() != null) {
            syncRepostCount(post.getRepostOfId());
        }

        // Ảnh trên Cloudinary cũng nên được dọn, nhưng lỗi phía Cloudinary
        // KHÔNG được phép làm hỏng thao tác xóa bài đã thực hiện xong.
        try {
            imageStorageService.deleteAlouteImageByUrl(post.getImageUrl());
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // Repost
    // ============================================================

    /**
     * Repost thuần là một RELATION document, không phải Post. Nhờ vậy feed chỉ có một card
     * bài gốc và dòng "X đã đăng lại" được gắn trực tiếp lên card đó.
     */
    public RepostResponse repost(Long currentUserId, String originalPostId, RepostRequest request) {
        Post original = getPostOrThrow(originalPostId);
        String finalOriginalId = original.getRepostOfId() != null ? original.getRepostOfId() : original.getId();
        if (!finalOriginalId.equals(original.getId())) original = getPostOrThrow(finalOriginalId);

        String userId = String.valueOf(currentUserId);
        var existing = repostRepository.findByUserIdAndPostId(userId, finalOriginalId);
        if (existing.isPresent()) {
            repostRepository.delete(existing.get());
            int newCount = syncRepostCount(finalOriginalId);
            RepostResponse response = new RepostResponse(finalOriginalId, false, newCount, null);
            response.setReposters(loadRepostActors(finalOriginalId));
            return response;
        }

        Repost repost = new Repost();
        repost.setUserId(userId);
        repost.setPostId(finalOriginalId);
        repost.setContent(request != null && request.getContent() != null && !request.getContent().isBlank()
                ? request.getContent().trim() : null);
        Repost saved = repostRepository.save(repost);
        int newCount = syncRepostCount(finalOriginalId);

        try {
            notificationService.notify(Long.valueOf(original.getUserId()), currentUserId, "REPOST", finalOriginalId,
                    "đã đăng lại bài viết của bạn");
        } catch (org.springframework.core.task.TaskRejectedException ignored) {
            // Không để notification queue làm hỏng thao tác repost đã lưu thành công.
        }
        RepostResponse response = new RepostResponse(finalOriginalId, true, newCount, saved.getId());
        response.setReposters(loadRepostActors(finalOriginalId));
        return response;
    }

    private int syncRepostCount(String postId) {
        int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(postId));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(postId)),
                new Update().set("repostCount", count), Post.class);
        return count;
    }

    public void adjustLikeCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query, new Update().inc("likeCount", delta), Post.class);
    }

    /**
     * Cập nhật atomically số reaction theo TỪNG loại emoji (reactionCounts.LOVE, .HAHA...)
     * VÀ tổng likeCount cùng lúc, để 1 request switch reaction (vd LIKE -> LOVE) chỉ cần
     * đúng 2 lệnh update thay vì phải đọc-sửa-ghi (dễ sai lệch khi nhiều request chạy song song).
     */
    public void adjustReactionCount(String postId, String reactionType, int delta) {
        String type = ReactionType.normalize(reactionType);
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query,
                new Update().inc("reactionCounts." + type, delta).inc("likeCount", delta), Post.class);
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
        dto.setCreatedAt(post.getCreatedAt());
        dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

        Long actorId = Long.valueOf(post.getUserId());
        dto.setUserId(actorId);
        userRepository.findById(actorId).ifPresent(user -> {
            dto.setUsername(user.getUsername());
            dto.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        });

        String interactionPostId = post.getRepostOfId() != null ? post.getRepostOfId() : post.getId();
        dto.setInteractionPostId(interactionPostId);

        // Một lượt repost chỉ là quan hệ chia sẻ. Tất cả dữ liệu tương tác được đọc từ bài gốc.
        Post interactionPost = post;
        if (post.getRepostOfId() != null) {
            interactionPost = postRepository.findById(post.getRepostOfId()).orElse(null);
        }

        if (interactionPost != null && interactionPost.getModerationStatus() != ModerationStatus.HIDDEN) {
            dto.setLikeCount(interactionPost.getLikeCount());
            dto.setCommentCount(interactionPost.getCommentCount());
            dto.setReactionCounts(interactionPost.getReactionCounts() != null
                    ? interactionPost.getReactionCounts() : new HashMap<>());
            dto.setRepostCount(interactionPost.getRepostCount());
            dto.setImageUrl(post.getRepostOfId() != null
                    && (post.getContent() == null || post.getContent().isBlank())
                            ? com.aloute.common.util.CloudinaryUrlUtil.feedImage(interactionPost.getImageUrl())
                            : com.aloute.common.util.CloudinaryUrlUtil.feedImage(post.getImageUrl()));
        } else {
            dto.setLikeCount(post.getLikeCount());
            dto.setCommentCount(post.getCommentCount());
            dto.setReactionCounts(post.getReactionCounts() != null ? post.getReactionCounts() : new HashMap<>());
            dto.setRepostCount(post.getRepostCount());
            dto.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(post.getImageUrl()));
        }

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);
            Like current = likeRepository.findByPostIdAndUserId(interactionPostId, userIdStr).orElse(null);
            dto.setLikedByCurrentUser(current != null);
            if (current != null) {
                dto.setCurrentUserReaction(current.getReactionType());
            }
            dto.setRepostedByCurrentUser(
                    repostRepository.findByUserIdAndPostId(userIdStr, interactionPostId).isPresent());
        }

        if (post.getRepostOfId() != null) {
            dto.setRepostOf(buildRepostOrigin(post.getRepostOfId()));
        }
        attachRecentReposters(java.util.List.of(dto));

        return dto;
    }

    /** Dựng thông tin rút gọn của bài viết GỐC để lồng vào 1 bài repost. */
    private PostResponse.RepostOrigin buildRepostOrigin(String originalPostId) {
        PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
        origin.setPostId(originalPostId);

        Post original = postRepository.findById(originalPostId).orElse(null);
        if (original == null || original.getModerationStatus() == ModerationStatus.HIDDEN) {
            origin.setDeleted(true);
            return origin;
        }

        origin.setContent(original.getContent());
        origin.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(original.getImageUrl()));
        origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));
        // Số đăng lại THẬT SỰ của bài gốc — xem javadoc của RepostOrigin.repostCount.
        origin.setRepostCount(original.getRepostCount());

        Long originalUserId = Long.valueOf(original.getUserId());
        origin.setUserId(originalUserId);
        userRepository.findById(originalUserId).ifPresent(user -> {
            origin.setUsername(user.getUsername());
            origin.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        });

        return origin;
    }

    /**
     * Chuyển 1 danh sách Post thành PostResponse theo lô (batch).
     *
     * QUY ƯỚC QUAN TRỌNG VỚI REPOST:
     * - Document Post của lượt repost chỉ là QUAN HỆ "user này đã chia sẻ bài X".
     * - Nó KHÔNG phải một bài viết độc lập để like/comment.
     * - Mọi reaction/comment và bộ đếm hiển thị trên một repost đều lấy từ BÀI GỐC.
     * - DTO vẫn giữ id của document repost để có thể xóa/toggle chính lượt chia sẻ đó;
     *   interactionPostId mới là ID dùng cho các tương tác với nội dung.
     */
    private List<PostResponse> toResponses(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> userIds = posts.stream()
                .map(post -> Long.valueOf(post.getUserId()))
                .collect(Collectors.toSet());

        Map<Long, User> usersById = new HashMap<>(userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity())));

        Set<String> repostOfIds = posts.stream()
                .map(Post::getRepostOfId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, Post> originalsById = repostOfIds.isEmpty() ? Collections.emptyMap()
                : postRepository.findAllById(repostOfIds).stream()
                        .collect(Collectors.toMap(Post::getId, Function.identity()));

        if (!originalsById.isEmpty()) {
            Set<Long> originalAuthorIds = originalsById.values().stream()
                    .map(p -> Long.valueOf(p.getUserId()))
                    .filter(id -> !usersById.containsKey(id))
                    .collect(Collectors.toSet());
            if (!originalAuthorIds.isEmpty()) {
                userRepository.findAllById(originalAuthorIds)
                        .forEach(u -> usersById.put(u.getId(), u));
            }
        }

        // Với mỗi item đang hiển thị, interactionPostId chính là bài gốc nếu item là repost.
        List<String> interactionPostIds = posts.stream()
                .map(p -> p.getRepostOfId() != null ? p.getRepostOfId() : p.getId())
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> reactionByPostId = new HashMap<>();
        Set<String> repostedByCurrentUserIds = Collections.emptySet();

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);

            // Lấy reaction của currentUser trên BÀI GỐC, không phải trên document repost.
            for (Like like : likeRepository.findByPostIdInAndUserId(interactionPostIds, userIdStr)) {
                reactionByPostId.put(like.getPostId(), like.getReactionType());
            }

            repostedByCurrentUserIds = repostRepository
                    .findByUserIdAndPostIdIn(userIdStr, interactionPostIds).stream()
                    .map(Repost::getPostId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        final Map<String, String> finalReactionByPostId = reactionByPostId;
        final Set<String> finalRepostedIds = repostedByCurrentUserIds;

        return posts.stream()
                .filter(post -> post.getModerationStatus() != ModerationStatus.HIDDEN)
                .map(post -> {
                    PostResponse dto = new PostResponse();

                    // id vẫn là document đang hiển thị. Với repost đó là document quan hệ chia sẻ.
                    dto.setId(post.getId());
                    dto.setContent(post.getContent()); // quote text; null/rỗng nếu repost thuần
                    dto.setCreatedAt(post.getCreatedAt()); // thời điểm chia sẻ
                    dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

                    Long actorId = Long.valueOf(post.getUserId());
                    dto.setUserId(actorId);
                    User actor = usersById.get(actorId);
                    if (actor != null) {
                        dto.setUsername(actor.getUsername());
                        dto.setUserAvatarUrl(AvatarUtil.resolve(actor.getAvatarUrl()));
                    }

                    String interactionPostId = post.getRepostOfId() != null
                            ? post.getRepostOfId()
                            : post.getId();
                    dto.setInteractionPostId(interactionPostId);

                    // Nội dung + toàn bộ số liệu tương tác luôn lấy từ bài gốc nếu đây là repost.
                    Post interactionPost = post.getRepostOfId() != null
                            ? originalsById.get(post.getRepostOfId())
                            : post;

                    if (interactionPost != null && interactionPost.getModerationStatus() != ModerationStatus.HIDDEN) {
                        dto.setLikeCount(interactionPost.getLikeCount());
                        dto.setCommentCount(interactionPost.getCommentCount());
                        dto.setReactionCounts(interactionPost.getReactionCounts() != null
                                ? interactionPost.getReactionCounts()
                                : new HashMap<>());
                        dto.setRepostCount(interactionPost.getRepostCount());
                        // Với repost thuần, trả luôn media của bài gốc ở top-level để API consumer
                        // không phải tự giải nén repostOf chỉ để lấy hình. Quote vẫn giữ image null.
                        if (post.getRepostOfId() != null && (post.getContent() == null || post.getContent().isBlank())) {
                            dto.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(interactionPost.getImageUrl()));
                        } else {
                            dto.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(post.getImageUrl()));
                        }
                    } else {
                        dto.setLikeCount(post.getLikeCount());
                        dto.setCommentCount(post.getCommentCount());
                        dto.setReactionCounts(post.getReactionCounts() != null ? post.getReactionCounts() : new HashMap<>());
                        dto.setRepostCount(post.getRepostCount());
                        dto.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(post.getImageUrl()));
                    }

                    String currentReaction = finalReactionByPostId.get(interactionPostId);
                    dto.setCurrentUserReaction(currentReaction);
                    dto.setLikedByCurrentUser(currentReaction != null);
                    dto.setRepostedByCurrentUser(finalRepostedIds.contains(interactionPostId));

                    if (post.getRepostOfId() != null) {
                        Post original = originalsById.get(post.getRepostOfId());
                        PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
                        origin.setPostId(post.getRepostOfId());
                        if (original == null || original.getModerationStatus() == ModerationStatus.HIDDEN) {
                            origin.setDeleted(true);
                        } else {
                            origin.setContent(original.getContent());
                            origin.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(original.getImageUrl()));
                            origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));
                            origin.setRepostCount(original.getRepostCount());
                            Long originalAuthorId = Long.valueOf(original.getUserId());
                            origin.setUserId(originalAuthorId);
                            User originalAuthor = usersById.get(originalAuthorId);
                            if (originalAuthor != null) {
                                origin.setUsername(originalAuthor.getUsername());
                                origin.setUserAvatarUrl(AvatarUtil.resolve(originalAuthor.getAvatarUrl()));
                            }
                        }
                        dto.setRepostOf(origin);
                    }

                    return dto;
                })
                .collect(Collectors.toList());
    }

    /** Gắn tên người repost lên bài gốc; tối đa 3 avatar/name để tránh query/UI phình. */
    private void attachRecentReposters(List<PostResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        List<String> postIds = responses.stream()
                .map(PostResponse::getInteractionPostId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (postIds.isEmpty()) return;

        List<Repost> reposts = repostRepository.findByPostIdIn(postIds);
        if (reposts.isEmpty()) return;
        Set<Long> userIds = reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, List<Repost>> byPost = reposts.stream().collect(Collectors.groupingBy(Repost::getPostId));

        for (PostResponse response : responses) {
            List<Repost> rs = new java.util.ArrayList<>(byPost.getOrDefault(response.getInteractionPostId(), Collections.emptyList()));
            rs.sort(java.util.Comparator.comparing(Repost::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).reversed());
            List<PostResponse.RepostActor> actors = rs.stream().map(r -> toRepostActor(r, users)).
                    filter(java.util.Objects::nonNull).toList();
            response.setRecentReposters(actors.stream().limit(3).toList());
            response.setOtherReposters(actors.stream().skip(3).toList());
        }
    }

    private PostResponse.RepostActor toRepostActor(Repost repost, Map<Long, User> users) {
        Long userId = Long.valueOf(repost.getUserId());
        User user = users.get(userId);
        if (user == null) return null;
        PostResponse.RepostActor actor = new PostResponse.RepostActor();
        actor.setUserId(userId);
        actor.setUsername(user.getUsername());
        actor.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        actor.setCreatedAt(repost.getCreatedAt());
        return actor;
    }

    private List<PostResponse.RepostActor> loadRepostActors(String postId) {
        List<Repost> reposts = repostRepository.findByPostIdOrderByCreatedAtDesc(postId);
        if (reposts.isEmpty()) return Collections.emptyList();
        Set<Long> ids = reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return reposts.stream().map(r -> toRepostActor(r, users))
                .filter(java.util.Objects::nonNull).toList();
    }
}