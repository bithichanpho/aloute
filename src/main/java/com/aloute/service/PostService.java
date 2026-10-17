package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.ReactionType;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.request.RepostRequest;
import com.aloute.dto.response.ModerationResult;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.RepostResponse;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aloute.dto.response.FeedCursorPage;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final ContentAccessService access;
    private final RepostPresentationService repostPresentationService;
    private final PostResponseMapper postResponseMapper;

    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final ILikeRepository likeRepository;
    private final ModerationService moderationService;
    private final NotificationService notificationService;
    private final MongoTemplate mongoTemplate;
    private final ICommentRepository commentRepository;
    private final INotificationRepository notificationRepository;
    private final IRepostRepository repostRepository;
    private final PostModerationCoordinator moderationCoordinator;
    private final MediaAssetLifecycleService postMediaAssets;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private final MongoTransactionRetryExecutor transactionRetryExecutor;

    @Autowired
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
            ContentAccessService access,
            RepostPresentationService repostPresentationService,
            PostResponseMapper postResponseMapper,
            PostModerationCoordinator moderationCoordinator,
            MediaAssetLifecycleService postMediaAssets,
            FeedCacheInvalidator feedCacheInvalidator,
            MongoTransactionRetryExecutor transactionRetryExecutor) {
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
        this.repostPresentationService = repostPresentationService;
        this.postResponseMapper = postResponseMapper;
        this.moderationCoordinator = moderationCoordinator;
        this.postMediaAssets = postMediaAssets;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.transactionRetryExecutor = transactionRetryExecutor;
    }

    /** Compatibility constructor retained for unit tests that construct PostService directly. */
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
            ContentAccessService access,
            RepostPresentationService repostPresentationService,
            PostResponseMapper postResponseMapper,
            PostModerationCoordinator moderationCoordinator,
            MediaAssetLifecycleService postMediaAssets) {
        this(postRepository, userRepository, likeRepository, moderationService, notificationService,
                mongoTemplate, commentRepository, notificationRepository, repostRepository, access,
                repostPresentationService, postResponseMapper, moderationCoordinator, postMediaAssets,
                null, null);
    }

    public PostResponse createPost(Long currentUserId, CreatePostRequest request) {
        return createPost(currentUserId, request, null);
    }

    public PostResponse createPost(
            Long currentUserId, CreatePostRequest request, String communityId) {
        // Production moderation runs after persistence on a bounded background executor. The
        // post remains hidden from every viewer except its author until the decision is stored.
        if (postMediaAssets != null) {
            postMediaAssets.validatePostMediaUrl(request.getImageUrl(), null, String.valueOf(currentUserId));
            postMediaAssets.validatePostMediaUrl(request.getVideoUrl(), null, String.valueOf(currentUserId));
        }
        ModerationResult initialResult = moderationService.isEnabled()
                ? null
                : moderationService.check(request.getContent());
        ModerationStatus initialStatus = moderationService.isEnabled()
                ? ModerationStatus.PENDING
                : moderationService.resolveStatus(initialResult);

        Post post = new Post();
        post.setUserId(String.valueOf(currentUserId));
        post.setContent(request.getContent());
        post.setImageUrl(request.getImageUrl());
        post.setVideoUrl(request.getVideoUrl());
        post.setVisibility(request.getVisibility());
        post.setHashtags(ContentTagService.extract(request.getContent(), '#'));
        post.setMentions(ContentTagService.extract(request.getContent(), '@'));
        post.setModerationStatus(initialStatus);
        post.setToxicityScore(initialResult == null ? 0.0 : initialResult.getScore());
        post.setCommunityId(communityId);
        post.setNotifyMentionsAfterModeration(initialStatus == ModerationStatus.PENDING);

        Post saved = postRepository.save(post);
        markPostMediaAttached(saved.getId(), String.valueOf(currentUserId), saved.getImageUrl(), saved.getVideoUrl());
        if (initialStatus == ModerationStatus.PENDING && moderationCoordinator != null) {
            afterCommitSafely("post moderation enqueue", () -> moderationCoordinator.submit(saved.getId()));
        } else {
            notifyReadableMentions(saved, currentUserId);
        }
        PostResponse response = toResponse(saved, currentUserId);
        invalidateViewerFeedCache(currentUserId);
        return response;
    }

    void notifyReadableMentions(Post saved, Long authorId) {
        if (saved == null || saved.getMentions() == null
                || saved.getModerationStatus() == ModerationStatus.HIDDEN
                || saved.getModerationStatus() == ModerationStatus.PENDING) return;
        for (String mention : saved.getMentions()) {
            userRepository.findByUsernameIgnoreCase(mention)
                    .filter(u -> access.canRead(saved, u.getId()))
                    .ifPresent(u -> afterCommitSafely("mention notification",
                            () -> notificationService.notify(u.getId(), authorId, "MENTION",
                                    saved.getId(), "đã nhắc đến bạn trong bài viết")));
        }
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

    /**
     * Stable chronological keyset pagination. The cursor points at the last raw document
     * consumed, not a page number, so new posts do not shift already visited pages.
     * The single feed traversal implementation deliberately prioritizes stable, scalable
     * keyset scrolling and avoids a bounded in-memory ranking window.
     */
    @Cacheable(
            cacheNames = "feedPages",
            key = "T(com.aloute.service.FeedCacheKey).create(#currentUserId, #followingIds, #tab, #hashtag, #encodedCursor, #requestedSize)",
            condition = "#currentUserId != null")
    public FeedCursorPage getCursorFeed(
            Long currentUserId,
            List<Long> followingIds,
            String tab,
            String hashtag,
            String encodedCursor,
            int requestedSize) {
        int size = Math.max(1, Math.min(30, requestedSize));
        boolean followingOnly = "following".equalsIgnoreCase(tab);
        List<String> followed = followingIds == null
                ? List.of()
                : followingIds.stream().filter(java.util.Objects::nonNull)
                        .map(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
        if (currentUserId != null && !followed.contains(String.valueOf(currentUserId))) {
            // Following feed includes the viewer's own posts as well as followed accounts.
            followed.add(String.valueOf(currentUserId));
        }
        if (followingOnly && followed.isEmpty())
            return new FeedCursorPage(List.of(), "", false);

        String normalizedTag = normalizeHashtag(hashtag);
        CursorPoint cursor = decodeFeedCursor(encodedCursor);
        String nextCursor = encodedCursor == null ? "" : encodedCursor;
        List<PostResponse> result = new ArrayList<>(size);
        final int batchSize = Math.min(150, Math.max(50, size * 4));
        boolean hasMore = false;
        int batches = 0;

        while (result.size() < size && batches < 20) {
            List<Criteria> clauses = new ArrayList<>();
            clauses.add(Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN));
            clauses.add(new Criteria().orOperator(
                    Criteria.where("repostOfId").is(null),
                    new Criteria().andOperator(
                            Criteria.where("repostOfId").ne(null),
                            Criteria.where("content").nin(null, ""))));
            if (followingOnly) clauses.add(Criteria.where("userId").in(followed));
            if (!normalizedTag.isBlank()) clauses.add(Criteria.where("hashtags").is(normalizedTag));
            if (cursor != null) {
                clauses.add(new Criteria().orOperator(
                        Criteria.where("createdAt").lt(cursor.createdAt()),
                        new Criteria().andOperator(
                                Criteria.where("createdAt").is(cursor.createdAt()),
                                Criteria.where("id").lt(cursor.id()))));
            }

            Query query = Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                    .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                    .limit(batchSize);
            List<Post> loaded = mongoTemplate.find(query, Post.class);
            if (loaded.isEmpty()) {
                hasMore = false;
                break;
            }

            ContentAccessService.AccessBatch permissions = access.forPosts(loaded, currentUserId);
            boolean stoppedAtPageBoundary = false;
            for (int i = 0; i < loaded.size(); i++) {
                Post candidate = loaded.get(i);
                if (permissions.canRead(candidate)) {
                    List<PostResponse> mapped = toReadableResponses(List.of(candidate), currentUserId, permissions);
                    if (!mapped.isEmpty()) result.add(mapped.get(0));
                }
                nextCursor = encodeFeedCursor(candidate);
                cursor = new CursorPoint(candidate.getCreatedAt(), candidate.getId());
                if (result.size() >= size) {
                    // The next request starts immediately after the last consumed document.
                    hasMore = i < loaded.size() - 1 || loaded.size() == batchSize;
                    stoppedAtPageBoundary = true;
                    break;
                }
            }
            if (stoppedAtPageBoundary) break;
            if (loaded.size() < batchSize) {
                hasMore = false;
                break;
            }
            hasMore = true;
            batches++;
        }
        if (batches >= 20 && result.size() < size) hasMore = true;
        if (result.isEmpty() && !hasMore) nextCursor = "";
        repostPresentationService.attachRecentReposters(result);
        return new FeedCursorPage(result, hasMore ? nextCursor : (result.isEmpty() ? "" : nextCursor), hasMore);
    }

    public List<PostResponse> getPostsByHashtag(String hashtag, Long currentUserId, int size) {
        return getCursorFeed(currentUserId, List.of(), "foryou", hashtag, null, size).items();
    }

    private String normalizeHashtag(String hashtag) {
        if (hashtag == null || hashtag.isBlank()) return "";
        String value = hashtag.trim().replaceFirst("^#", "").toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[\\p{L}\\p{N}_]{1,50}"))
            throw new BadRequestException("Hashtag không hợp lệ");
        return value;
    }

    private String encodeFeedCursor(Post post) {
        String value = post.getCreatedAt().toString() + "|" + post.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private CursorPoint decodeFeedCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int divider = decoded.indexOf('|');
            if (divider <= 0 || divider == decoded.length() - 1) throw new IllegalArgumentException();
            LocalDateTime createdAt = LocalDateTime.parse(decoded.substring(0, divider));
            String id = decoded.substring(divider + 1);
            if (id.length() > 100) throw new IllegalArgumentException();
            return new CursorPoint(createdAt, id);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Cursor feed không hợp lệ");
        }
    }

    private record CursorPoint(LocalDateTime createdAt, String id) {}

    /**
     * Compatibility adapter for the legacy page-number API. All feed traversal now uses the
     * cursor implementation, so there is no separate 200-post in-memory ranking window.
     * New clients should use /api/posts/feed/cursor directly.
     */
    public List<PostResponse> getFeed(
            Long currentUserId, List<Long> followingIds, int page, int size) {
        return readLegacyPageWithCursor(currentUserId, followingIds, "foryou", page, size);
    }

    public List<PostResponse> getFeedByFollowing(
            Long currentUserId, List<Long> followingIds, int page, int size) {
        return readLegacyPageWithCursor(currentUserId, followingIds, "following", page, size);
    }

    private List<PostResponse> readLegacyPageWithCursor(
            Long currentUserId, List<Long> followingIds, String tab, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(30, requestedSize));
        // Bound the compatibility endpoint against abusive page-number requests. Deep browsing
        // uses the cursor endpoint and does not have an artificial page or 200-post ceiling.
        if (page > 100) throw new BadRequestException(
                "Hãy dùng phân trang cursor cho các trang sâu hơn; trang số tối đa của API cũ là 100");

        String cursor = null;
        FeedCursorPage result = null;
        for (int i = 0; i <= page; i++) {
            result = getCursorFeed(currentUserId, followingIds, tab, null, cursor, size);
            if (i < page && !result.hasMore()) return List.of();
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                if (i < page) return List.of();
            }
        }
        return result == null ? List.of() : result.items();
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
        // Permission checks may reject rows after Mongo reads them. Page over the visible
        // sequence, not the raw sequence, so invisible rows cannot shorten pages.
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(50, size));
        if (safePage > 100) throw new BadRequestException("Trang quá sâu, vui lòng dùng cursor");
        long skipVisible = (long) safePage * safeSize;
        java.util.ArrayList<PostResponse> output = new java.util.ArrayList<>(safeSize);
        int rawPage = 0;
        final int chunk = 100;
        final int maxScan = 20000;
        while (rawPage * chunk < maxScan) {
            Pageable batch = PageRequest.of(rawPage++, chunk,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")));
            List<Post> raw = mongoTemplate.find(ownTimelineQuery(userId, batch), Post.class);
            if (raw.isEmpty()) return output;
            for (PostResponse readable : toResponses(raw, currentUserId)) {
                if (skipVisible > 0) { skipVisible--; continue; }
                output.add(readable);
                if (output.size() == safeSize) return output;
            }
            if (raw.size() < chunk) return output;
        }
        throw new BadRequestException("Quá nhiều bài không hiển thị; hãy dùng phân trang cursor");
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
        int boundedLimit = Math.max(1, Math.min(20, limit));
        Query query;
        if (search.startsWith("#")) {
            String tag = search.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
            if (tag.isBlank()) return Collections.emptyList();
            // Hashtags are already materialized and indexed; avoid a full-content regex scan.
            query = Query.query(Criteria.where("hashtags").is(tag));
        } else {
            // Uses the MongoDB text index on Post.content instead of an unanchored regex scan.
            query = Query.query(TextCriteria.forLanguage("none").matching(search));
        }
        query.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(boundedLimit);
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
        return mongoTemplate.count(new Query(new Criteria().andOperator(criteria,
                Criteria.where("visibility").is(com.aloute.model.document.PostVisibility.PUBLIC))), Post.class);
    }

    public void deletePost(String postId, Long currentUserId) {
        if (transactionRetryExecutor == null) {
            deletePostInTransaction(postId, currentUserId);
            return;
        }
        transactionRetryExecutor.execute(() -> {
            deletePostInTransaction(postId, currentUserId);
            return null;
        });
    }

    private void deletePostInTransaction(String postId, Long currentUserId) {
        Post post =
                postRepository
                        .findById(postId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy bài viết"));

        if (!post.getUserId().equals(String.valueOf(currentUserId))) {
            throw new ForbiddenException("Bạn không có quyền xóa bài viết này");
        }

        // All Mongo collections that make up the post are deleted in one Mongo transaction.
        // PostgreSQL notifications and Cloudinary cleanup are external side effects, so defer
        // them until Mongo commits; this avoids deleting external state if the local transaction
        // rolls back. These stores still require reconciliation/outbox handling for hard failures.
        likeRepository.deleteByPostId(postId);
        java.util.List<String> commentIds = mongoTemplate.find(
                Query.query(Criteria.where("postId").is(postId)),
                com.aloute.model.document.Comment.class).stream()
                .map(com.aloute.model.document.Comment::getId).toList();
        if (!commentIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("commentId").in(commentIds)),
                    com.aloute.model.document.CommentReaction.class);
        }
        commentRepository.deleteByPostId(postId);
        repostRepository.deleteByPostId(postId);
        postRepository.delete(post);
        if (post.getRepostOfId() != null) {
            syncRepostCount(post.getRepostOfId());
        }

        afterCommitSafely("post notification cleanup", () -> notificationRepository.deleteByReferenceId(postId));
        // A deleted post can be present in many viewers' cached pages, not only its author's feed.
        // This is a rare content-removal path, so invalidate globally using the SCAN-based strategy.
        invalidateAllFeedCache();
        afterCommitSafely("Cloudinary media cleanup", () -> {
            if (postMediaAssets == null) {
                // Compatibility constructors are used only by isolated tests. Production must
                // have the lifecycle service; do not reintroduce an unsafe direct-delete path.
                log.warn("Post media cleanup skipped because the lifecycle service is not configured (postId={})",
                        postId);
                return;
            }
            postMediaAssets.deletePostMedia(post);
        });
    }

    public RepostResponse repost(Long currentUserId, String originalPostId, RepostRequest request) {
        try {
            RepostResponse response = executeMongoTransaction(
                    () -> repostInTransaction(currentUserId, originalPostId, request));
            invalidateViewerFeedCache(currentUserId);
            return response;
        } catch (DuplicateKeyException duplicateRepost) {
            // A second concurrent insert for the unique (userId, postId) pair is an already
            // completed repost, not a server error. Resolve it after the failed transaction rolls back.
            if (request != null && Boolean.FALSE.equals(request.getReposted())) {
                RepostResponse reconciled = executeMongoTransaction(
                        () -> repostInTransaction(currentUserId, originalPostId, request));
                invalidateViewerFeedCache(currentUserId);
                return reconciled;
            }
            Post original = access.requirePost(originalPostId, currentUserId);
            String finalOriginalId = original.getRepostOfId() != null
                    ? original.getRepostOfId() : original.getId();
            var existing = repostRepository.findByUserIdAndPostId(
                    String.valueOf(currentUserId), finalOriginalId);
            if (existing.isEmpty()) throw duplicateRepost;
            int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(finalOriginalId));
            RepostResponse response = new RepostResponse(
                    finalOriginalId, true, count, existing.get().getId());
            response.setReposters(repostPresentationService.loadRepostActors(finalOriginalId));
            invalidateViewerFeedCache(currentUserId);
            return response;
        }
    }

    private RepostResponse repostInTransaction(
            Long currentUserId, String originalPostId, RepostRequest request) {
        Post original = access.requirePost(originalPostId, currentUserId);
        String finalOriginalId = original.getRepostOfId() != null
                ? original.getRepostOfId() : original.getId();
        if (!finalOriginalId.equals(original.getId())) original = getPostOrThrow(finalOriginalId);

        String userId = String.valueOf(currentUserId);
        var existing = repostRepository.findByUserIdAndPostId(userId, finalOriginalId);
        boolean hasDesiredState = request != null && request.getReposted() != null;
        boolean desiredReposted = hasDesiredState && Boolean.TRUE.equals(request.getReposted());

        if (existing.isPresent() && hasDesiredState && desiredReposted) {
            // Replaying the same repost=true request is a read, not an un-repost toggle.
            int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(finalOriginalId));
            RepostResponse response = new RepostResponse(finalOriginalId, true, count, existing.get().getId());
            response.setReposters(repostPresentationService.loadRepostActors(finalOriginalId));
            return response;
        }
        if (existing.isEmpty() && hasDesiredState && !desiredReposted) {
            // Replaying repost=false when already absent is also idempotent.
            int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(finalOriginalId));
            RepostResponse response = new RepostResponse(finalOriginalId, false, count, null);
            response.setReposters(repostPresentationService.loadRepostActors(finalOriginalId));
            return response;
        }
        if (existing.isPresent() && (!hasDesiredState || !desiredReposted)) {
            repostRepository.delete(existing.get());
            int newCount = syncRepostCount(finalOriginalId);
            RepostResponse response = new RepostResponse(finalOriginalId, false, newCount, null);
            response.setReposters(repostPresentationService.loadRepostActors(finalOriginalId));
            return response;
        }

        Repost repost = new Repost();
        repost.setUserId(userId);
        repost.setPostId(finalOriginalId);
        repost.setContent(request != null && request.getContent() != null && !request.getContent().isBlank()
                ? request.getContent().trim() : null);
        Repost saved = repostRepository.save(repost);
        int newCount = syncRepostCount(finalOriginalId);

        final Long originalOwnerId = Long.valueOf(original.getUserId());
        afterCommitSafely("repost notification", () -> notificationService.notify(
                originalOwnerId, currentUserId, "REPOST", finalOriginalId, "đã đăng lại bài viết của bạn"));
        RepostResponse response = new RepostResponse(finalOriginalId, true, newCount, saved.getId());
        response.setReposters(repostPresentationService.loadRepostActors(finalOriginalId));
        return response;
    }

    private <T> T executeMongoTransaction(java.util.function.Supplier<T> work) {
        return transactionRetryExecutor == null ? work.get() : transactionRetryExecutor.execute(work);
    }

    /** Invalidate only one viewer on ordinary feed mutations; rare security/moderation events may clear all. */
    public void invalidateViewerFeedCache(Long viewerId) {
        com.aloute.common.util.TransactionCommitHooks.afterCommit(() -> {
            if (feedCacheInvalidator != null) feedCacheInvalidator.invalidateViewer(viewerId);
        });
    }

    public void invalidateAllFeedCache() {
        com.aloute.common.util.TransactionCommitHooks.afterCommit(() -> {
            if (feedCacheInvalidator != null) feedCacheInvalidator.invalidateAll();
        });
    }

    private void markPostMediaAttached(String postId, String uploaderId, String imageUrl, String videoUrl) {
        if (postMediaAssets == null) return;
        try {
            postMediaAssets.attachPostMedia(imageUrl, postId, uploaderId);
            if (videoUrl == null || !videoUrl.equals(imageUrl)) {
                postMediaAssets.attachPostMedia(videoUrl, postId, uploaderId);
            }
        } catch (RuntimeException trackingFailure) {
            // The bounded cleaner recognizes referenced UPLOADED assets after a partial tracking failure.
            log.warn("Post media attachment tracking deferred (postId={}, cause={})",
                    postId, trackingFailure.getClass().getSimpleName());
        }
    }

    private void afterCommitSafely(String operation, Runnable action) {
        TransactionCommitHooks.afterCommit(
                () -> {
                    try {
                        action.run();
                    } catch (RuntimeException ex) {
                        log.error("Post-commit side effect failed (operation={}, cause={})",
                                operation, ex.getClass().getSimpleName(), ex);
                    }
                });
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
        return postResponseMapper.toResponse(post, currentUserId);
    }

    private List<PostResponse> toResponses(List<Post> posts, Long currentUserId) {
        return postResponseMapper.toResponses(posts, currentUserId);
    }

    private List<PostResponse> toReadableResponses(
            List<Post> posts, Long currentUserId, ContentAccessService.AccessBatch permissions) {
        return postResponseMapper.toReadableResponses(posts, currentUserId, permissions);
    }

}
