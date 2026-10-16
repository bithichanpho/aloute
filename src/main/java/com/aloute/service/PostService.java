package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.ReactionType;
import com.aloute.common.util.TimeAgoUtil;
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

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService {

    private final ContentAccessService access;

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
            ImageStorageService imageStorageService,
            ContentAccessService access) {
        this.access = access;

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

    public PostResponse createPost(
            Long currentUserId, CreatePostRequest request, String communityId) {
        ModerationResult moderation = moderationService.check(request.getContent());
        ModerationStatus status = moderationService.resolveStatus(moderation.getScore());
        if (!moderationService.isMediaApproved(request.getImageUrl(), "image"))
            throw new com.aloute.common.exception.BadRequestException(
                    "Ảnh không vượt qua kiểm duyệt an toàn");
        if (!moderationService.isMediaApproved(request.getVideoUrl(), "video"))
            throw new com.aloute.common.exception.BadRequestException(
                    "Video không vượt qua kiểm duyệt an toàn");

        Post post = new Post();
        post.setUserId(String.valueOf(currentUserId));
        post.setContent(request.getContent());
        post.setImageUrl(request.getImageUrl());
        post.setVideoUrl(request.getVideoUrl());
        post.setVisibility(request.getVisibility());
        post.setHashtags(ContentTagService.extract(request.getContent(), '#'));
        post.setMentions(ContentTagService.extract(request.getContent(), '@'));
        post.setModerationStatus(status);
        post.setToxicityScore(moderation.getScore());
        post.setCommunityId(communityId);

        Post saved = postRepository.save(post);
        for (String mention : post.getMentions())
            userRepository
                    .findByUsernameIgnoreCase(mention)
                    .filter(u -> access.canRead(saved, u.getId()))
                    .ifPresent(
                            u ->
                                    notificationService.notify(
                                            u.getId(),
                                            currentUserId,
                                            "MENTION",
                                            saved.getId(),
                                            "đã nhắc đến bạn trong bài viết"));
        return toResponse(saved, currentUserId);
    }

    private Query ownTimelineQuery(Long userId, Pageable pageable) {
        Criteria hasOwnCaption =
                new Criteria()
                        .andOperator(
                                Criteria.where("content").ne(null),
                                Criteria.where("content").ne(""));
        Criteria criteria =
                Criteria.where("userId")
                        .is(String.valueOf(userId))
                        .and("moderationStatus")
                        .ne(ModerationStatus.HIDDEN)
                        .orOperator(Criteria.where("repostOfId").is(null), hasOwnCaption);
        return new Query(criteria).with(pageable);
    }

    public List<PostResponse> getFeed(
            Long currentUserId, List<Long> followingIds, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(20, size));
        int candidateLimit = (int) Math.min(200L, Math.max(40L, (safePage + 1L) * safeSize * 2));
        List<Post> candidates;
        ContentAccessService.AccessBatch permissions;
        while (true) {
            List<Post> loaded =
                    postRepository.findAllForFeed(
                            PageRequest.of(
                                    0, candidateLimit, Sort.by(Sort.Direction.DESC, "createdAt")));
            permissions = access.forPosts(loaded, currentUserId);
            candidates = new java.util.ArrayList<>(permissions.readable(loaded));
            if (candidates.size() >= (safePage + 1L) * safeSize
                    || loaded.size() < candidateLimit
                    || candidateLimit == 200) break;
            candidateLimit = Math.min(200, candidateLimit * 2);
        }

        Set<String> followedAuthors =
                followingIds == null
                        ? Set.of()
                        : followingIds.stream().map(String::valueOf).collect(Collectors.toSet());
        Map<String, Integer> friendRepostSignals = Map.of();
        if (!followedAuthors.isEmpty() && !candidates.isEmpty()) {
            List<Repost> friendReposts =
                    repostRepository.findByUserIdIn(
                            new java.util.ArrayList<>(followedAuthors), PageRequest.of(0, 500));
            friendRepostSignals =
                    friendReposts.stream()
                            .map(Repost::getPostId)
                            .collect(Collectors.toMap(Function.identity(), id -> 1, Integer::sum));
        }
        Map<String, Integer> repostSignals = friendRepostSignals;
        candidates.sort(
                Comparator.comparingDouble(
                                (Post post) ->
                                        feedScore(
                                                post,
                                                followedAuthors.contains(post.getUserId()),
                                                repostSignals.getOrDefault(post.getId(), 0)))
                        .reversed()
                        .thenComparing(
                                Post::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(
                                Post::getId, Comparator.nullsLast(Comparator.reverseOrder())));

        int from = (int) Math.min((long) safePage * safeSize, candidates.size());
        int to = Math.min(from + safeSize, candidates.size());
        if (from >= to) return Collections.emptyList();
        List<PostResponse> result =
                toReadableResponses(candidates.subList(from, to), currentUserId, permissions);
        attachRecentReposters(result);
        return result;
    }

    public List<PostResponse> getFeedByFollowing(
            Long currentUserId, List<Long> followingIds, int page, int size) {
        if (followingIds == null || followingIds.isEmpty()) return Collections.emptyList();

        Pageable pageable =
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(20, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        List<String> ids = followingIds.stream().map(String::valueOf).collect(Collectors.toList());
        List<PostResponse> result =
                toResponses(
                        postRepository.findByUserIdInOrderByCreatedAtDesc(ids, pageable),
                        currentUserId);
        attachRecentReposters(result);
        return result;
    }

    double feedScore(Post post, boolean followedAuthor, int friendReposts) {
        long ageSeconds =
                post.getCreatedAt() == null
                        ? 604_800L
                        : Math.max(
                                0L,
                                java.time.Duration.between(
                                                post.getCreatedAt(), java.time.LocalDateTime.now())
                                        .getSeconds());
        double freshness = Math.exp(-ageSeconds / 86_400.0) * 100.0;
        double engagement =
                Math.log1p(
                                Math.max(0, post.getLikeCount())
                                        + Math.max(0, post.getCommentCount()) * 3.0
                                        + Math.max(0, post.getRepostCount()) * 4.0)
                        * 14.0;
        double relationship = followedAuthor ? 32.0 : 0.0;
        double socialProof = Math.min(3, Math.max(0, friendReposts)) * 18.0;
        return freshness + engagement + relationship + socialProof;
    }

    public List<PostResponse> getFeedByCommunity(
            String communityId, Long currentUserId, int page, int size) {
        Pageable pageable =
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(20, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts =
                postRepository.findByCommunityIdOrderByCreatedAtDesc(communityId, pageable);
        return toResponses(posts, currentUserId);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId) {
        return getPostsByUser(userId, currentUserId, 0, 20);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId, int page, int size) {
        Pageable pageable =
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(20, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = mongoTemplate.find(ownTimelineQuery(userId, pageable), Post.class);
        return toResponses(posts, currentUserId);
    }

    public List<PostResponse> getRepostsByUser(
            Long userId, Long currentUserId, int page, int size) {
        Pageable pageable =
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(20, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Repost> reposts =
                repostRepository.findByUserIdOrderByCreatedAtDesc(String.valueOf(userId), pageable);
        if (reposts.isEmpty()) return Collections.emptyList();

        List<String> ids = reposts.stream().map(Repost::getPostId).toList();
        Map<String, Post> originals =
                postRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Post> ordered =
                ids.stream().map(originals::get).filter(java.util.Objects::nonNull).toList();
        return toResponses(ordered, currentUserId);
    }

    public List<PostResponse> getPostsByIds(List<String> ids, Long currentUserId) {
        if (ids.isEmpty()) return List.of();
        Map<String, Post> posts =
                postRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        return toResponses(
                ids.stream().map(posts::get).filter(java.util.Objects::nonNull).toList(),
                currentUserId);
    }

    public List<PostResponse> searchPosts(String keyword, Long currentUserId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }

        String search = keyword.trim();
        Criteria searchCriteria =
                Criteria.where("content").regex(java.util.regex.Pattern.quote(search), "i");
        if (search.startsWith("#")) {
            String tag = search.substring(1).toLowerCase(java.util.Locale.ROOT);
            searchCriteria =
                    new Criteria()
                            .orOperator(
                                    Criteria.where("hashtags").is(tag),
                                    Criteria.where("content")
                                            .regex(
                                                    "(?<![\\p{L}\\p{N}_])#"
                                                            + java.util.regex.Pattern.quote(tag)
                                                            + "(?![\\p{L}\\p{N}_])",
                                                    "i"));
        }
        Query query =
                Query.query(searchCriteria)
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(Math.max(1, Math.min(30, limit)));

        return toResponses(mongoTemplate.find(query, Post.class), currentUserId);
    }

    public long countPostsByUser(Long userId) {
        Criteria hasOwnCaption =
                new Criteria()
                        .andOperator(
                                Criteria.where("content").ne(null),
                                Criteria.where("content").ne(""));
        Criteria criteria =
                Criteria.where("userId")
                        .is(String.valueOf(userId))
                        .and("moderationStatus")
                        .ne(ModerationStatus.HIDDEN)
                        .orOperator(Criteria.where("repostOfId").is(null), hasOwnCaption);
        return mongoTemplate.count(new Query(criteria), Post.class);
    }

    public void deletePost(String postId, Long currentUserId) {
        Post post =
                postRepository
                        .findById(postId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy bài viết"));

        if (!post.getUserId().equals(String.valueOf(currentUserId))) {
            throw new ForbiddenException("Bạn không có quyền xóa bài viết này");
        }

        likeRepository.deleteByPostId(postId);
        commentRepository.deleteByPostId(postId);
        postRepository.delete(post);
        notificationRepository.deleteByReferenceId(postId);

        repostRepository.deleteByPostId(postId);
        if (post.getRepostOfId() != null) {
            syncRepostCount(post.getRepostOfId());
        }

        try {
            imageStorageService.deleteAlouteImageByUrl(post.getImageUrl());
            imageStorageService.deleteAlouteMediaByUrl(post.getVideoUrl());
        } catch (Exception ignored) {
        }
    }

    public RepostResponse repost(Long currentUserId, String originalPostId, RepostRequest request) {
        Post original = access.requirePost(originalPostId, currentUserId);
        String finalOriginalId =
                original.getRepostOfId() != null ? original.getRepostOfId() : original.getId();
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
        repost.setContent(
                request != null && request.getContent() != null && !request.getContent().isBlank()
                        ? request.getContent().trim()
                        : null);
        Repost saved = repostRepository.save(repost);
        int newCount = syncRepostCount(finalOriginalId);

        try {
            notificationService.notify(
                    Long.valueOf(original.getUserId()),
                    currentUserId,
                    "REPOST",
                    finalOriginalId,
                    "đã đăng lại bài viết của bạn");
        } catch (org.springframework.core.task.TaskRejectedException ignored) {

        }
        RepostResponse response =
                new RepostResponse(finalOriginalId, true, newCount, saved.getId());
        response.setReposters(loadRepostActors(finalOriginalId));
        return response;
    }

    private int syncRepostCount(String postId) {
        int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(postId));
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(postId)),
                new Update().set("repostCount", count),
                Post.class);
        return count;
    }

    public void adjustLikeCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query, new Update().inc("likeCount", delta), Post.class);
    }

    public void adjustReactionCount(String postId, String reactionType, int delta) {
        String type = ReactionType.normalize(reactionType);
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(
                query,
                new Update().inc("reactionCounts." + type, delta).inc("likeCount", delta),
                Post.class);
    }

    public void adjustCommentCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        mongoTemplate.updateFirst(query, new Update().inc("commentCount", delta), Post.class);
    }

    public PostResponse getPost(String postId, Long currentUserId) {
        Post post = access.requirePost(postId, currentUserId);
        return toResponse(post, currentUserId);
    }

    public Post requireReadablePost(String postId, Long currentUserId) {
        return access.requirePost(postId, currentUserId);
    }

    public Post getPostOrThrow(String postId) {
        return postRepository
                .findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết"));
    }

    private PostResponse toResponse(Post post, Long currentUserId) {
        PostResponse dto = new PostResponse();
        dto.setId(post.getId());
        dto.setVisibility(post.getVisibility());
        dto.setContent(post.getContent());
        dto.setCreatedAt(post.getCreatedAt());
        dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

        Long actorId = Long.valueOf(post.getUserId());
        dto.setUserId(actorId);
        userRepository
                .findById(actorId)
                .ifPresent(
                        user -> {
                            dto.setUsername(user.getUsername());
                            dto.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
                        });

        String interactionPostId =
                post.getRepostOfId() != null ? post.getRepostOfId() : post.getId();
        dto.setInteractionPostId(interactionPostId);

        Post interactionPost = post;
        if (post.getRepostOfId() != null) {
            interactionPost = postRepository.findById(post.getRepostOfId()).orElse(null);
        }

        if (interactionPost != null
                && interactionPost.getModerationStatus() != ModerationStatus.HIDDEN) {
            dto.setLikeCount(interactionPost.getLikeCount());
            dto.setCommentCount(interactionPost.getCommentCount());
            dto.setReactionCounts(
                    interactionPost.getReactionCounts() != null
                            ? interactionPost.getReactionCounts()
                            : new HashMap<>());
            dto.setRepostCount(interactionPost.getRepostCount());
            dto.setImageUrl(
                    post.getRepostOfId() != null
                                    && (post.getContent() == null || post.getContent().isBlank())
                            ? com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                    interactionPost.getImageUrl())
                            : com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                    post.getImageUrl()));
            dto.setVideoUrl(
                    post.getRepostOfId() != null
                                    && (post.getContent() == null || post.getContent().isBlank())
                            ? interactionPost.getVideoUrl()
                            : post.getVideoUrl());
        } else {
            dto.setLikeCount(post.getLikeCount());
            dto.setCommentCount(post.getCommentCount());
            dto.setReactionCounts(
                    post.getReactionCounts() != null ? post.getReactionCounts() : new HashMap<>());
            dto.setRepostCount(post.getRepostCount());
            dto.setImageUrl(com.aloute.common.util.CloudinaryUrlUtil.feedImage(post.getImageUrl()));
            dto.setVideoUrl(post.getVideoUrl());
        }

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);
            Like current =
                    likeRepository.findByPostIdAndUserId(interactionPostId, userIdStr).orElse(null);
            dto.setLikedByCurrentUser(current != null);
            if (current != null) {
                dto.setCurrentUserReaction(current.getReactionType());
            }
            dto.setRepostedByCurrentUser(
                    repostRepository
                            .findByUserIdAndPostId(userIdStr, interactionPostId)
                            .isPresent());
        }

        if (post.getRepostOfId() != null) {
            dto.setRepostOf(buildRepostOrigin(post.getRepostOfId()));
        }
        attachRecentReposters(java.util.List.of(dto));

        return dto;
    }

    private PostResponse.RepostOrigin buildRepostOrigin(String originalPostId) {
        PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
        origin.setPostId(originalPostId);

        Post original = postRepository.findById(originalPostId).orElse(null);
        if (original == null || original.getModerationStatus() == ModerationStatus.HIDDEN) {
            origin.setDeleted(true);
            return origin;
        }

        origin.setContent(original.getContent());
        origin.setImageUrl(
                com.aloute.common.util.CloudinaryUrlUtil.feedImage(original.getImageUrl()));
        origin.setVideoUrl(original.getVideoUrl());
        origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));

        origin.setRepostCount(original.getRepostCount());

        Long originalUserId = Long.valueOf(original.getUserId());
        origin.setUserId(originalUserId);
        userRepository
                .findById(originalUserId)
                .ifPresent(
                        user -> {
                            origin.setUsername(user.getUsername());
                            origin.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
                        });

        return origin;
    }

    private List<PostResponse> toResponses(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        ContentAccessService.AccessBatch permissions = access.forPosts(posts, currentUserId);
        return toReadableResponses(permissions.readable(posts), currentUserId, permissions);
    }

    private List<PostResponse> toReadableResponses(
            List<Post> posts, Long currentUserId, ContentAccessService.AccessBatch permissions) {
        if (posts.isEmpty()) return List.of();
        Map<Long, User> usersById = permissions.users();
        Map<String, Post> originalsById = permissions.originals();

        List<String> interactionPostIds =
                posts.stream()
                        .map(p -> p.getRepostOfId() != null ? p.getRepostOfId() : p.getId())
                        .distinct()
                        .collect(Collectors.toList());

        Map<String, String> reactionByPostId = new HashMap<>();
        Set<String> repostedByCurrentUserIds = Collections.emptySet();

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);

            for (Like like :
                    likeRepository.findByPostIdInAndUserId(interactionPostIds, userIdStr)) {
                reactionByPostId.put(like.getPostId(), like.getReactionType());
            }

            repostedByCurrentUserIds =
                    repostRepository.findByUserIdAndPostIdIn(userIdStr, interactionPostIds).stream()
                            .map(Repost::getPostId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet());
        }

        final Map<String, String> finalReactionByPostId = reactionByPostId;
        final Set<String> finalRepostedIds = repostedByCurrentUserIds;

        return posts.stream()
                .map(
                        post -> {
                            PostResponse dto = new PostResponse();

                            dto.setId(post.getId());
                            dto.setVisibility(post.getVisibility());
                            dto.setContent(post.getContent());
                            dto.setCreatedAt(post.getCreatedAt());
                            dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

                            Long actorId = Long.valueOf(post.getUserId());
                            dto.setUserId(actorId);
                            User actor = usersById.get(actorId);
                            if (actor != null) {
                                dto.setUsername(actor.getUsername());
                                dto.setUserAvatarUrl(AvatarUtil.resolve(actor.getAvatarUrl()));
                            }

                            String interactionPostId =
                                    post.getRepostOfId() != null
                                            ? post.getRepostOfId()
                                            : post.getId();
                            dto.setInteractionPostId(interactionPostId);

                            Post interactionPost =
                                    post.getRepostOfId() != null
                                            ? originalsById.get(post.getRepostOfId())
                                            : post;

                            if (interactionPost != null
                                    && interactionPost.getModerationStatus()
                                            != ModerationStatus.HIDDEN) {
                                dto.setLikeCount(interactionPost.getLikeCount());
                                dto.setCommentCount(interactionPost.getCommentCount());
                                dto.setReactionCounts(
                                        interactionPost.getReactionCounts() != null
                                                ? interactionPost.getReactionCounts()
                                                : new HashMap<>());
                                dto.setRepostCount(interactionPost.getRepostCount());

                                if (post.getRepostOfId() != null
                                        && (post.getContent() == null
                                                || post.getContent().isBlank())) {
                                    dto.setImageUrl(
                                            com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                                    interactionPost.getImageUrl()));
                                    dto.setVideoUrl(interactionPost.getVideoUrl());
                                } else {
                                    dto.setImageUrl(
                                            com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                                    post.getImageUrl()));
                                    dto.setVideoUrl(post.getVideoUrl());
                                }
                            } else {
                                dto.setLikeCount(post.getLikeCount());
                                dto.setCommentCount(post.getCommentCount());
                                dto.setReactionCounts(
                                        post.getReactionCounts() != null
                                                ? post.getReactionCounts()
                                                : new HashMap<>());
                                dto.setRepostCount(post.getRepostCount());
                                dto.setImageUrl(
                                        com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                                post.getImageUrl()));
                                dto.setVideoUrl(post.getVideoUrl());
                            }

                            String currentReaction = finalReactionByPostId.get(interactionPostId);
                            dto.setCurrentUserReaction(currentReaction);
                            dto.setLikedByCurrentUser(currentReaction != null);
                            dto.setRepostedByCurrentUser(
                                    finalRepostedIds.contains(interactionPostId));

                            if (post.getRepostOfId() != null) {
                                Post original = originalsById.get(post.getRepostOfId());
                                PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
                                origin.setPostId(post.getRepostOfId());
                                if (original == null
                                        || original.getModerationStatus()
                                                == ModerationStatus.HIDDEN) {
                                    origin.setDeleted(true);
                                } else {
                                    origin.setContent(original.getContent());
                                    origin.setImageUrl(
                                            com.aloute.common.util.CloudinaryUrlUtil.feedImage(
                                                    original.getImageUrl()));
                                    origin.setVideoUrl(original.getVideoUrl());
                                    origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));
                                    origin.setRepostCount(original.getRepostCount());
                                    Long originalAuthorId = Long.valueOf(original.getUserId());
                                    origin.setUserId(originalAuthorId);
                                    User originalAuthor = usersById.get(originalAuthorId);
                                    if (originalAuthor != null) {
                                        origin.setUsername(originalAuthor.getUsername());
                                        origin.setUserAvatarUrl(
                                                AvatarUtil.resolve(originalAuthor.getAvatarUrl()));
                                    }
                                }
                                dto.setRepostOf(origin);
                            }

                            return dto;
                        })
                .collect(Collectors.toList());
    }

    private void attachRecentReposters(List<PostResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        List<String> postIds =
                responses.stream()
                        .map(PostResponse::getInteractionPostId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        if (postIds.isEmpty()) return;

        List<Repost> reposts = repostRepository.findByPostIdIn(postIds);
        if (reposts.isEmpty()) return;
        Set<Long> userIds =
                reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users =
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, List<Repost>> byPost =
                reposts.stream().collect(Collectors.groupingBy(Repost::getPostId));

        for (PostResponse response : responses) {
            List<Repost> rs =
                    new java.util.ArrayList<>(
                            byPost.getOrDefault(
                                    response.getInteractionPostId(), Collections.emptyList()));
            rs.sort(
                    java.util.Comparator.comparing(
                                    Repost::getCreatedAt,
                                    java.util.Comparator.nullsLast(
                                            java.util.Comparator.naturalOrder()))
                            .reversed());
            List<PostResponse.RepostActor> actors =
                    rs.stream()
                            .map(r -> toRepostActor(r, users))
                            .filter(java.util.Objects::nonNull)
                            .toList();
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
        Set<Long> ids =
                reposts.stream().map(r -> Long.valueOf(r.getUserId())).collect(Collectors.toSet());
        Map<Long, User> users =
                userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        return reposts.stream()
                .map(r -> toRepostActor(r, users))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
