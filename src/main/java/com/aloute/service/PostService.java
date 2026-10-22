package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.util.ReactionType;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.request.RepostRequest;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.RepostResponse;
import com.aloute.dto.response.ReposterCursorPage;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.Repost;
import com.aloute.model.document.SavedPost;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.TextCriteria;
import com.aloute.persistence.postgres.document.Update;
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
    private final NotificationService notificationService;
    private final PostgresDocumentStore documentStore;
    private final ICommentRepository commentRepository;
    private final INotificationRepository notificationRepository;
    private final IRepostRepository repostRepository;
    private final MediaAssetLifecycleService postMediaAssets;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private final PostgresTransactionRetryExecutor transactionRetryExecutor;
    private final IFriendRepository friendRepository;
    private final INotificationOutboxRepository notificationOutboxRepository;
    private final ModerationService moderation;
    private final PostModerationCoordinator moderationCoordinator;

    @Autowired
    public PostService(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            ModerationService moderationService,
            NotificationService notificationService,
            PostgresDocumentStore documentStore,
            ICommentRepository commentRepository,
            INotificationRepository notificationRepository,
            IRepostRepository repostRepository,
            ContentAccessService access,
            RepostPresentationService repostPresentationService,
            PostResponseMapper postResponseMapper,
            PostModerationCoordinator moderationCoordinator,
            MediaAssetLifecycleService postMediaAssets,
            FeedCacheInvalidator feedCacheInvalidator,
            PostgresTransactionRetryExecutor transactionRetryExecutor,
            IFriendRepository friendRepository,
            INotificationOutboxRepository notificationOutboxRepository) {
        this.access = access;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.notificationService = notificationService;
        this.documentStore = documentStore;
        this.commentRepository = commentRepository;
        this.notificationRepository = notificationRepository;
        this.repostRepository = repostRepository;
        this.repostPresentationService = repostPresentationService;
        this.postResponseMapper = postResponseMapper;
        this.postMediaAssets = postMediaAssets;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.transactionRetryExecutor = transactionRetryExecutor;
        this.friendRepository = friendRepository;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.moderation = moderationService;
        this.moderationCoordinator = moderationCoordinator;
    }

    /** Compatibility constructor retained for tests and callers predating notification outbox cleanup. */
    public PostService(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            ModerationService moderationService,
            NotificationService notificationService,
            PostgresDocumentStore documentStore,
            ICommentRepository commentRepository,
            INotificationRepository notificationRepository,
            IRepostRepository repostRepository,
            ContentAccessService access,
            RepostPresentationService repostPresentationService,
            PostResponseMapper postResponseMapper,
            PostModerationCoordinator moderationCoordinator,
            MediaAssetLifecycleService postMediaAssets,
            FeedCacheInvalidator feedCacheInvalidator,
            PostgresTransactionRetryExecutor transactionRetryExecutor,
            IFriendRepository friendRepository) {
        this(postRepository, userRepository, likeRepository, moderationService, notificationService,
                documentStore, commentRepository, notificationRepository, repostRepository, access,
                repostPresentationService, postResponseMapper, moderationCoordinator, postMediaAssets,
                feedCacheInvalidator, transactionRetryExecutor, friendRepository, null);
    }

    /** Compatibility constructor retained for unit tests that construct PostService directly. */
    public PostService(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            ModerationService moderationService,
            NotificationService notificationService,
            PostgresDocumentStore documentStore,
            ICommentRepository commentRepository,
            INotificationRepository notificationRepository,
            IRepostRepository repostRepository,
            ContentAccessService access,
            RepostPresentationService repostPresentationService,
            PostResponseMapper postResponseMapper,
            PostModerationCoordinator moderationCoordinator,
            MediaAssetLifecycleService postMediaAssets) {
        this(postRepository, userRepository, likeRepository, moderationService, notificationService,
                documentStore, commentRepository, notificationRepository, repostRepository, access,
                repostPresentationService, postResponseMapper, moderationCoordinator, postMediaAssets,
                null, null, null);
    }

    @Cacheable(
            cacheNames = "feedPages",
            key = "T(com.aloute.service.FeedCacheKey).createForViewer(#currentUserId, #tab, #hashtag, #encodedCursor, #requestedSize)",
            condition = "#currentUserId != null")
    public FeedCursorPage getCursorFeedForViewer(
            Long currentUserId,
            String tab,
            String hashtag,
            String encodedCursor,
            int requestedSize) {
        if (friendRepository == null) {
            return getCursorFeed(currentUserId, List.of(), tab, hashtag, encodedCursor, requestedSize);
        }
        return readCursorFeed(
                currentUserId, null, true, tab, hashtag, encodedCursor, requestedSize);
    }

    public PostResponse createPost(Long currentUserId, CreatePostRequest request) {
        return createPost(currentUserId, request, null);
    }

    public PostResponse createPost(
            Long currentUserId, CreatePostRequest request, String communityId) {
        return createPost(currentUserId, request, communityId, null);
    }

    public PostResponse createPost(
            Long currentUserId,
            CreatePostRequest request,
            String communityId,
            CommunityPostStatus communityPostStatus) {
        if (postMediaAssets != null) {
            postMediaAssets.validatePostMediaUrl(request.getImageUrl(), null, String.valueOf(currentUserId));
            postMediaAssets.validatePostMediaUrl(request.getVideoUrl(), null, String.valueOf(currentUserId));
        }
        // Fail closed: while moderation is enabled a new post is PENDING (visible only to its
        // author) until PostModerationWorker records a decision. Skipping this step would let any
        // user publish unreviewed text/media. Only the explicit local/demo switch
        // (MODERATION_ENABLED=false) publishes directly, because the queue is a no-op in that mode.
        // Community approval (communityPostStatus) is a separate, independent gate.
        boolean moderationRequired = isModerationRequired();
        ModerationStatus initialStatus =
                moderationRequired ? ModerationStatus.PENDING : ModerationStatus.APPROVED;

        Post post = new Post();
        post.setUserId(String.valueOf(currentUserId));
        post.setContent(request.getContent());
        post.setImageUrl(request.getImageUrl());
        post.setVideoUrl(request.getVideoUrl());
        post.setVisibility(request.getVisibility());
        post.setHashtags(ContentTagService.extract(request.getContent(), '#'));
        post.setMentions(ContentTagService.extract(request.getContent(), '@'));
        post.setModerationStatus(initialStatus);
        post.setToxicityScore(0.0);
        post.setCommunityId(communityId);
        post.setCommunityPostStatus(communityPostStatus);
        // Mentions are delivered by the worker only if the post is approved.
        post.setNotifyMentionsAfterModeration(moderationRequired);

        Post saved = postRepository.save(post);
        markPostMediaAttached(saved.getId(), String.valueOf(currentUserId), saved.getImageUrl(), saved.getVideoUrl());
        if (moderationRequired) {
            dispatchModeration(saved.getId());
        }
        // Skips PENDING/HIDDEN posts, so mentions are only sent immediately in demo mode.
        notifyReadableMentions(saved, currentUserId);
        PostResponse response = toResponse(saved, currentUserId);
        invalidateViewerFeedCache(currentUserId);
        return response;
    }

    private boolean isModerationRequired() {
        return moderation != null && moderation.isEnabled();
    }

    /**
     * Queues the post once the surrounding transaction (if any) has committed. A failed or
     * skipped dispatch is safe: PostModerationCoordinator#recoverPendingPosts claims every PENDING
     * post whose lease is unset, so the post is never approved by default.
     */
    private void dispatchModeration(String postId) {
        if (moderationCoordinator == null) return;
        afterCommitSafely("post moderation dispatch", () -> moderationCoordinator.submit(postId));
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
        return new Query(ownTimelineCriteria(userId)).with(pageable);
    }

    private Criteria ownTimelineCriteria(Long userId) {
        Criteria hasOwnCaption =
                new Criteria()
                        .andOperator(
                                Criteria.where("content").ne(null),
                                Criteria.where("content").ne(""));
        return Criteria.where("userId")
                        .is(String.valueOf(userId))
                        .and("moderationStatus")
                        .ne(ModerationStatus.HIDDEN)
                        .orOperator(Criteria.where("repostOfId").is(null), hasOwnCaption);
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
        return readCursorFeed(
                currentUserId, followingIds, false, tab, hashtag, encodedCursor, requestedSize);
    }

    private FeedCursorPage readCursorFeed(
            Long currentUserId,
            List<Long> followingIds,
            boolean streamFollowingIds,
            String tab,
            String hashtag,
            String encodedCursor,
            int requestedSize) {
        int size = Math.max(1, Math.min(30, requestedSize));
        boolean followingOnly = "following".equalsIgnoreCase(tab);
        List<String> followed = followingIds == null
                ? new ArrayList<>()
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
            if (!normalizedTag.isBlank()) clauses.add(Criteria.where("hashtags").is(normalizedTag));
            if (cursor != null) {
                clauses.add(new Criteria().orOperator(
                        Criteria.where("createdAt").lt(cursor.createdAt()),
                        new Criteria().andOperator(
                                Criteria.where("createdAt").is(cursor.createdAt()),
                                Criteria.where("id").lt(cursor.id()))));
            }

            List<Post> loaded = followingOnly && streamFollowingIds
                    ? loadFollowingFeedBatch(clauses, currentUserId, batchSize)
                    : loadFeedBatch(clauses, followingOnly ? followed : List.of(), batchSize);
            if (loaded.isEmpty()) {
                hasMore = false;
                break;
            }

            ContentAccessService.AccessBatch permissions = access.forPosts(loaded, currentUserId);
            List<Post> readable = permissions.readable(loaded);
            Map<String, PostResponse> responsesById =
                    toReadableResponses(readable, currentUserId, permissions).stream()
                            .collect(Collectors.toMap(PostResponse::getId, Function.identity()));
            boolean stoppedAtPageBoundary = false;
            for (int i = 0; i < loaded.size(); i++) {
                Post candidate = loaded.get(i);
                PostResponse mapped = responsesById.get(candidate.getId());
                if (mapped != null) result.add(mapped);
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

    /**
     * Traverses SQL follow edges in keyset batches and keeps only the newest PostgreSQL candidates.
     * Memory usage is bounded by roughly two feed batches regardless of follow count.
     */
    private List<Post> loadFollowingFeedBatch(
            List<Criteria> baseClauses, Long viewerId, int limit) {
        final int relationshipBatchSize = 500;
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        List<Post> newest = new ArrayList<>(limit * 2);
        Long afterId = 0L;
        boolean firstBatch = true;

        while (true) {
            List<Long> ids = friendRepository.findFollowedIdsAfter(
                    viewerId, afterId, PageRequest.of(0, relationshipBatchSize));
            List<String> chunk = ids.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
            if (firstBatch) {
                chunk.add(String.valueOf(viewerId));
                firstBatch = false;
            }
            if (!chunk.isEmpty()) {
                List<Criteria> clauses = new ArrayList<>(baseClauses);
                clauses.add(Criteria.where("userId").in(chunk));
                newest.addAll(documentStore.find(
                        Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                                .with(sort).limit(limit),
                        Post.class));
                newest.sort(java.util.Comparator
                        .comparing(Post::getCreatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(Post::getId,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
                if (newest.size() > limit) newest.subList(limit, newest.size()).clear();
            }
            if (ids.size() < relationshipBatchSize) break;
            afterId = ids.get(ids.size() - 1);
        }
        return newest;
    }

    /**
     * Keeps PostgreSQL {@code $in} predicates bounded for accounts following many users. Each chunk is
     * index-backed and returns only the requested window; the small sorted windows are then merged.
     */
    private List<Post> loadFeedBatch(List<Criteria> baseClauses, List<String> followed, int limit) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        if (followed == null || followed.isEmpty()) {
            Query query = Query.query(new Criteria().andOperator(baseClauses.toArray(Criteria[]::new)))
                    .with(sort).limit(limit);
            return documentStore.find(query, Post.class);
        }

        final int inClauseSize = 500;
        if (followed.size() <= inClauseSize) {
            List<Criteria> clauses = new ArrayList<>(baseClauses);
            clauses.add(Criteria.where("userId").in(followed));
            return documentStore.find(
                    Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                            .with(sort).limit(limit),
                    Post.class);
        }

        List<Post> candidates = new ArrayList<>();
        for (int start = 0; start < followed.size(); start += inClauseSize) {
            List<Criteria> clauses = new ArrayList<>(baseClauses);
            clauses.add(Criteria.where("userId").in(
                    followed.subList(start, Math.min(start + inClauseSize, followed.size()))));
            candidates.addAll(documentStore.find(
                    Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                            .with(sort).limit(limit),
                    Post.class));
        }
        candidates.sort(java.util.Comparator
                .comparing(Post::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(Post::getId, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return candidates.size() <= limit ? candidates : new ArrayList<>(candidates.subList(0, limit));
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
        return encodeCursor(post.getCreatedAt(), post.getId());
    }

    private String encodeCursor(LocalDateTime createdAt, String id) {
        String value = createdAt.toString() + "|" + id;
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

    public List<PostResponse> getCommunityPostsForReview(
            String communityId, Long currentUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.max(1, Math.min(50, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return postRepository
                .findCommunityPostsByStatus(communityId, CommunityPostStatus.PENDING, pageable)
                .stream()
                .map(post -> postResponseMapper.toResponse(post, currentUserId))
                .toList();
    }

    public Post getPostForCommunityManagement(String postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết"));
    }

    public PostResponse decideCommunityPost(
            String postId,
            String communityId,
            Long reviewerId,
            CommunityPostStatus decision) {
        if (decision != CommunityPostStatus.APPROVED
                && decision != CommunityPostStatus.REJECTED) {
            throw new BadRequestException("Quyết định duyệt bài không hợp lệ");
        }
        Post saved = documentStore.findAndModify(
                Query.query(Criteria.where("_id").is(postId)
                        .and("communityId").is(communityId)
                        .and("communityPostStatus").is(CommunityPostStatus.PENDING)),
                new Update().set("communityPostStatus", decision),
                com.aloute.persistence.postgres.document.FindAndModifyOptions.options().returnNew(true),
                Post.class);
        if (saved == null) {
            throw new BadRequestException(
                    "Bài viết không tồn tại trong cộng đồng hoặc không còn chờ duyệt");
        }
        invalidateAllFeedCache();
        return postResponseMapper.toResponse(saved, reviewerId);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId) {
        return getPostsByUser(userId, currentUserId, 0, 20);
    }

    public List<PostResponse> getPostsByUser(Long userId, Long currentUserId, int page, int size) {
        // Permission checks may reject rows after PostgreSQL reads them. Page over the visible
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
            List<Post> raw = documentStore.find(ownTimelineQuery(userId, batch), Post.class);
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

    public FeedCursorPage getPostsByUserCursor(
            Long userId, Long currentUserId, String encodedCursor, int requestedSize) {
        int size = Math.max(1, Math.min(50, requestedSize));
        CursorPoint cursor = decodeFeedCursor(encodedCursor);
        String nextCursor = encodedCursor == null ? "" : encodedCursor;
        List<PostResponse> result = new ArrayList<>(size);
        final int batchSize = Math.min(150, Math.max(50, size * 3));
        boolean hasMore = false;

        for (int batch = 0; batch < 20 && result.size() < size; batch++) {
            List<Criteria> clauses = new ArrayList<>();
            clauses.add(ownTimelineCriteria(userId));
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
            List<Post> loaded = documentStore.find(query, Post.class);
            if (loaded.isEmpty()) {
                hasMore = false;
                break;
            }
            ContentAccessService.AccessBatch permissions = access.forPosts(loaded, currentUserId);
            List<Post> readable = permissions.readable(loaded);
            Map<String, PostResponse> responsesById =
                    toReadableResponses(readable, currentUserId, permissions).stream()
                            .collect(Collectors.toMap(PostResponse::getId, Function.identity()));
            boolean stopped = false;
            for (int i = 0; i < loaded.size(); i++) {
                Post candidate = loaded.get(i);
                PostResponse mapped = responsesById.get(candidate.getId());
                if (mapped != null) result.add(mapped);
                nextCursor = encodeFeedCursor(candidate);
                cursor = new CursorPoint(candidate.getCreatedAt(), candidate.getId());
                if (result.size() >= size) {
                    hasMore = i < loaded.size() - 1 || loaded.size() == batchSize;
                    stopped = true;
                    break;
                }
            }
            if (stopped) break;
            hasMore = loaded.size() == batchSize;
            if (!hasMore) break;
        }
        repostPresentationService.attachRecentReposters(result);
        return new FeedCursorPage(result, nextCursor, hasMore);
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

    public FeedCursorPage getRepostsByUserCursor(
            Long userId, Long currentUserId, String encodedCursor, int requestedSize) {
        int size = Math.max(1, Math.min(50, requestedSize));
        CursorPoint cursor = decodeFeedCursor(encodedCursor);
        String nextCursor = encodedCursor == null ? "" : encodedCursor;
        List<PostResponse> result = new ArrayList<>(size);
        final int batchSize = Math.min(150, Math.max(50, size * 3));
        boolean hasMore = false;

        for (int batch = 0; batch < 20 && result.size() < size; batch++) {
            List<Criteria> clauses = new ArrayList<>();
            clauses.add(Criteria.where("userId").is(String.valueOf(userId)));
            if (cursor != null) {
                clauses.add(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("createdAt").lt(cursor.createdAt()),
                                        new Criteria()
                                                .andOperator(
                                                        Criteria.where("createdAt")
                                                                .is(cursor.createdAt()),
                                                        Criteria.where("_id").lt(cursor.id()))));
            }
            Query query =
                    Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                            .with(
                                    Sort.by(
                                            Sort.Order.desc("createdAt"),
                                            Sort.Order.desc("_id")))
                            .limit(batchSize);
            List<Repost> loaded = documentStore.find(query, Repost.class);
            if (loaded.isEmpty()) {
                hasMore = false;
                break;
            }

            List<String> postIds = loaded.stream().map(Repost::getPostId).distinct().toList();
            Map<String, Post> postsById =
                    postRepository.findAllById(postIds).stream()
                            .collect(Collectors.toMap(Post::getId, Function.identity()));
            List<Post> posts =
                    postIds.stream()
                            .map(postsById::get)
                            .filter(java.util.Objects::nonNull)
                            .toList();
            ContentAccessService.AccessBatch permissions = access.forPosts(posts, currentUserId);
            Map<String, PostResponse> responsesById =
                    toReadableResponses(permissions.readable(posts), currentUserId, permissions)
                            .stream()
                            .collect(Collectors.toMap(PostResponse::getId, Function.identity()));

            boolean stopped = false;
            for (int i = 0; i < loaded.size(); i++) {
                Repost candidate = loaded.get(i);
                PostResponse mapped = responsesById.get(candidate.getPostId());
                if (mapped != null) result.add(mapped);
                nextCursor = encodeCursor(candidate.getCreatedAt(), candidate.getId());
                cursor = new CursorPoint(candidate.getCreatedAt(), candidate.getId());
                if (result.size() >= size) {
                    hasMore = i < loaded.size() - 1 || loaded.size() == batchSize;
                    stopped = true;
                    break;
                }
            }
            if (stopped) break;
            hasMore = loaded.size() == batchSize;
            if (!hasMore) break;
        }
        return new FeedCursorPage(result, nextCursor, hasMore);
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
        return searchPosts(keyword, currentUserId, 0, limit);
    }

    public List<PostResponse> searchPosts(
            String keyword, Long currentUserId, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }

        String search = keyword.trim();
        int boundedPage = Math.max(0, Math.min(10_000, page));
        int boundedLimit = Math.max(1, Math.min(50, size));
        Query query;
        if (search.startsWith("#")) {
            String tag = search.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
            if (tag.isBlank()) return Collections.emptyList();
            // Hashtags are already materialized and indexed; avoid a full-content regex scan.
            query = Query.query(Criteria.where("hashtags").is(tag));
        } else {
            // Uses the PostgreSQL text index on Post.content instead of an unanchored regex scan.
            query = Query.query(TextCriteria.forLanguage("none").matching(search));
        }
        query.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .skip((long) boundedPage * boundedLimit)
                .limit(boundedLimit);
        return toResponses(documentStore.find(query, Post.class), currentUserId);
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
        return documentStore.count(new Query(new Criteria().andOperator(criteria,
                Criteria.where("visibility").is(com.aloute.model.document.PostVisibility.PUBLIC))), Post.class);
    }

    public void deletePost(String postId, Long currentUserId) {
        deletePost(postId, currentUserId, false, null);
    }

    public void deletePostAsModerator(
            String postId, Long currentUserId, String expectedCommunityId) {
        deletePost(postId, currentUserId, true, expectedCommunityId);
    }

    private void deletePost(
            String postId,
            Long currentUserId,
            boolean moderator,
            String expectedCommunityId) {
        if (transactionRetryExecutor == null) {
            deletePostInTransaction(postId, currentUserId, moderator, expectedCommunityId);
            return;
        }
        transactionRetryExecutor.execute(() -> {
            deletePostInTransaction(postId, currentUserId, moderator, expectedCommunityId);
            return null;
        });
    }

    private void deletePostInTransaction(
            String postId,
            Long currentUserId,
            boolean moderator,
            String expectedCommunityId) {
        Post post =
                postRepository
                        .findById(postId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy bài viết"));

        if (!moderator && !post.getUserId().equals(String.valueOf(currentUserId))) {
            throw new ForbiddenException("Bạn không có quyền xóa bài viết này");
        }
        if (moderator && !java.util.Objects.equals(expectedCommunityId, post.getCommunityId())) {
            throw new ResourceNotFoundException("Không tìm thấy bài viết trong cộng đồng này");
        }

        // All PostgreSQL document collections that make up the post are deleted in one PostgreSQL transaction.
        // PostgreSQL notifications and Cloudinary cleanup are external side effects, so defer
        // them until PostgreSQL commits; this avoids deleting external state if the local transaction
        // rolls back. These stores still require reconciliation/outbox handling for hard failures.
        likeRepository.deleteByPostId(postId);
        java.util.List<String> commentIds = documentStore.find(
                Query.query(Criteria.where("postId").is(postId)),
                com.aloute.model.document.Comment.class).stream()
                .map(com.aloute.model.document.Comment::getId).toList();
        if (!commentIds.isEmpty()) {
            documentStore.remove(Query.query(Criteria.where("commentId").in(commentIds)),
                    com.aloute.model.document.CommentReaction.class);
            documentStore.remove(Query.query(new Criteria().andOperator(
                    Criteria.where("targetType").is("COMMENT"),
                    Criteria.where("targetId").in(commentIds))), ContentReport.class);
        }
        commentRepository.deleteByPostId(postId);
        repostRepository.deleteByPostId(postId);
        Query byPost = Query.query(Criteria.where("postId").is(postId));
        documentStore.remove(byPost, SavedPost.class);
        documentStore.remove(byPost, PostRevision.class);
        documentStore.remove(Query.query(new Criteria().andOperator(
                Criteria.where("targetType").is("POST"),
                Criteria.where("targetId").is(postId))), ContentReport.class);
        postRepository.delete(post);
        if (postMediaAssets != null) {
            // The tombstone is committed with the PostgreSQL deletion. A process crash after commit can
            // no longer lose the Cloudinary cleanup request.
            postMediaAssets.queuePostMediaDeletion(post);
        }
        if (post.getRepostOfId() != null) {
            syncRepostSummary(post.getRepostOfId());
        }

        afterCommitSafely("post notification cleanup", () -> {
            notificationRepository.deleteByReferenceId(postId);
            if (notificationOutboxRepository != null) {
                notificationOutboxRepository.deleteByReferenceIdIn(List.of(postId));
            }
        });
        // A deleted post can be present in many viewers' cached pages, not only its author's feed.
        // This is a rare content-removal path, so invalidate globally using the SCAN-based strategy.
        invalidateAllFeedCache();
    }

    public RepostResponse repost(Long currentUserId, String originalPostId, RepostRequest request) {
        try {
            RepostResponse response = executeDatabaseTransaction(
                    () -> repostInTransaction(currentUserId, originalPostId, request));
            invalidateViewerFeedCache(currentUserId);
            return response;
        } catch (DuplicateKeyException duplicateRepost) {
            // A second concurrent insert for the unique (userId, postId) pair is an already
            // completed repost, not a server error. Resolve it after the failed transaction rolls back.
            if (request != null && Boolean.FALSE.equals(request.getReposted())) {
                RepostResponse reconciled = executeDatabaseTransaction(
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
            setRepostPreview(response, finalOriginalId);
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
            setRepostPreview(response, finalOriginalId);
            return response;
        }
        if (existing.isEmpty() && hasDesiredState && !desiredReposted) {
            // Replaying repost=false when already absent is also idempotent.
            int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(finalOriginalId));
            RepostResponse response = new RepostResponse(finalOriginalId, false, count, null);
            setRepostPreview(response, finalOriginalId);
            return response;
        }
        if (existing.isPresent() && (!hasDesiredState || !desiredReposted)) {
            repostRepository.delete(existing.get());
            RepostSummary summary = syncRepostSummary(finalOriginalId);
            RepostResponse response =
                    new RepostResponse(finalOriginalId, false, summary.count(), null);
            setRepostPreview(response, summary.reposterIds());
            return response;
        }

        Repost repost = new Repost();
        repost.setUserId(userId);
        repost.setPostId(finalOriginalId);
        repost.setContent(request != null && request.getContent() != null && !request.getContent().isBlank()
                ? request.getContent().trim() : null);
        Repost saved = repostRepository.save(repost);
        RepostSummary summary = syncRepostSummary(finalOriginalId);

        final Long originalOwnerId = Long.valueOf(original.getUserId());
        afterCommitSafely("repost notification", () -> notificationService.notify(
                originalOwnerId, currentUserId, "REPOST", finalOriginalId, "đã đăng lại bài viết của bạn"));
        RepostResponse response =
                new RepostResponse(finalOriginalId, true, summary.count(), saved.getId());
        setRepostPreview(response, summary.reposterIds());
        return response;
    }

    public ReposterCursorPage getReposters(
            Long currentUserId, String postId, String cursor, int size) {
        Post post = access.requirePost(postId, currentUserId);
        String interactionPostId =
                post.getRepostOfId() == null ? post.getId() : post.getRepostOfId();
        Post original = interactionPostId.equals(post.getId()) ? post : access.requirePost(
                interactionPostId, currentUserId);
        return repostPresentationService.loadRepostActorsPage(
                interactionPostId, cursor, size, original.getRepostCount());
    }

    private <T> T executeDatabaseTransaction(java.util.function.Supplier<T> work) {
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

    private RepostSummary syncRepostSummary(String postId) {
        int count = (int) Math.min(Integer.MAX_VALUE, repostRepository.countByPostId(postId));
        List<String> recentReposterIds = repostPresentationService.recentReposterIds(postId);
        documentStore.updateFirst(
                Query.query(Criteria.where("_id").is(postId)),
                new Update()
                        .set("repostCount", count)
                        .set("recentReposterIds", recentReposterIds),
                Post.class);
        return new RepostSummary(count, recentReposterIds);
    }

    private void setRepostPreview(RepostResponse response, String postId) {
        List<PostResponse.RepostActor> preview =
                repostPresentationService.loadRecentRepostActors(postId);
        response.setReposters(preview);
        response.setOtherReposterCount(response.getRepostCount() - preview.size());
    }

    private void setRepostPreview(RepostResponse response, List<String> reposterIds) {
        List<PostResponse.RepostActor> preview =
                repostPresentationService.loadActorsByIds(reposterIds);
        response.setReposters(preview);
        response.setOtherReposterCount(response.getRepostCount() - preview.size());
    }

    private record RepostSummary(int count, List<String> reposterIds) {}

    public void adjustLikeCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        documentStore.updateFirst(query, new Update().inc("likeCount", delta), Post.class);
    }

    public void adjustReactionCount(String postId, String reactionType, int delta) {
        String type = ReactionType.normalize(reactionType);
        Query query = Query.query(Criteria.where("_id").is(postId));
        documentStore.updateFirst(
                query,
                new Update().inc("reactionCounts." + type, delta).inc("likeCount", delta),
                Post.class);
    }

    public void adjustCommentCount(String postId, int delta) {
        Query query = Query.query(Criteria.where("_id").is(postId));
        documentStore.updateFirst(query, new Update().inc("commentCount", delta), Post.class);
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
