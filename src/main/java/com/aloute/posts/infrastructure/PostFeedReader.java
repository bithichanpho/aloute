package com.aloute.posts.infrastructure;

import com.aloute.posts.api.FeedPostReader;
import com.aloute.social.api.FollowedAccounts;
import com.aloute.model.document.Post;
import com.aloute.model.document.ModerationStatus;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.service.ContentAccessService;
import com.aloute.service.PostResponseMapper;
import com.aloute.service.RepostPresentationService;
import com.aloute.dto.response.PostResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Posts-owned query adapter. SQL predicates, ordering and authorization preserve the legacy feed. */
@Service
public class PostFeedReader implements FeedPostReader {
    private final PostgresDocumentStore documentStore;
    private final ContentAccessService access;
    private final PostResponseMapper mapper;
    private final RepostPresentationService reposts;
    private final FollowedAccounts followedAccounts;

    public PostFeedReader(PostgresDocumentStore documentStore, ContentAccessService access,
            PostResponseMapper mapper, RepostPresentationService reposts) {
        this(documentStore,access,mapper,reposts,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PostFeedReader(PostgresDocumentStore documentStore, ContentAccessService access,
            PostResponseMapper mapper, RepostPresentationService reposts, FollowedAccounts followedAccounts) {
        this.documentStore=documentStore;this.access=access;this.mapper=mapper;this.reposts=reposts;
        this.followedAccounts=followedAccounts;
    }

    public List<Candidate> readBatch(Long viewerId, List<String> followed, boolean streamFollowing,
            boolean followingOnly, String normalizedTag, Position before, int limit) {
        List<Criteria> clauses = new ArrayList<>();
        // A feed cursor needs a valid timestamp. Preserve malformed rows for explicit repair.
        clauses.add(Criteria.where("createdAt").validDateTime());
        clauses.add(Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN));
        clauses.add(new Criteria().orOperator(
                Criteria.where("repostOfId").is(null),
                new Criteria().andOperator(
                        Criteria.where("repostOfId").ne(null),
                        Criteria.where("content").nin(null, ""))));
        if (!normalizedTag.isBlank()) clauses.add(Criteria.where("hashtags").is(normalizedTag));
        if (before != null) {
            clauses.add(Criteria.keysetBefore("createdAt", before.createdAt(), "id", before.id()));
        }
        List<Post> loaded = followingOnly && streamFollowing
                ? loadFollowingFeedBatch(clauses, viewerId, limit)
                : loadFeedBatch(clauses, followingOnly ? followed : List.of(), limit);
        if (loaded.isEmpty()) return List.of();
        ContentAccessService.AccessBatch permissions = access.forPosts(loaded, viewerId);
        List<Post> readable = permissions.readable(loaded);
        Map<String, PostResponse> cards = mapper.toReadableResponses(readable, viewerId, permissions)
                .stream().collect(Collectors.toMap(PostResponse::getId, Function.identity()));
        return loaded.stream().map(post -> new Candidate(new Position(post.getCreatedAt(), post.getId()),
                cards.get(post.getId()))).toList();
    }

    public void attachRecentReposters(List<PostResponse> cards) {
        reposts.attachRecentReposters(cards);
    }

    public List<PostResponse> refreshCards(Long viewerId, List<String> postIds) {
        return refreshCards(viewerId, postIds, false);
    }

    public List<PostResponse> refreshFollowingCards(Long viewerId, List<String> postIds) {
        return refreshCards(viewerId, postIds, true);
    }

    private List<PostResponse> refreshCards(Long viewerId, List<String> postIds, boolean followingOnly) {
        if (postIds.size() > 30) throw new IllegalArgumentException("Cached feed page exceeds 30 cards");
        List<String> ids = postIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return List.of();
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                Criteria.where("createdAt").validDateTime(),
                Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN),
                new Criteria().orOperator(Criteria.where("repostOfId").is(null),
                        new Criteria().andOperator(Criteria.where("repostOfId").ne(null),
                                Criteria.where("content").nin(null, "")))));
        if (followingOnly) query.addCriteria(Criteria.where("userId").inReadView(FollowedAccounts.READ_VIEW, viewerId, viewerId));
        List<Post> loaded = documentStore.find(query.limit(ids.size()), Post.class);
        ContentAccessService.AccessBatch permissions = access.forPosts(loaded, viewerId);
        Map<String, PostResponse> cards = mapper.toReadableResponses(permissions.readable(loaded), viewerId, permissions)
                .stream().collect(Collectors.toMap(PostResponse::getId, Function.identity()));
        List<PostResponse> refreshed = ids.stream().map(cards::get).filter(java.util.Objects::nonNull).toList();
        reposts.attachRecentReposters(refreshed);
        return refreshed;
    }

    private List<Post> loadFollowingFeedBatch(
            List<Criteria> baseClauses, Long viewerId, int limit) {
        List<Criteria> clauses = new ArrayList<>(baseClauses);
        // Empty social scope otherwise scans the global time index looking for nonexistent authors.
        // This bounded existence probe is constant in follow count; all nonempty scopes remain set-based.
        clauses.add(followedAccounts != null && !followedAccounts.hasFollowing(viewerId)
                ? Criteria.where("userId").is(String.valueOf(viewerId))
                : Criteria.where("userId").inReadView(FollowedAccounts.READ_VIEW, viewerId, viewerId));
        return documentStore.find(Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))).limit(limit), Post.class);
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
            candidates.sort(java.util.Comparator
                    .comparing(Post::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                    .thenComparing(Post::getId, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
            if (candidates.size() > limit) candidates.subList(limit, candidates.size()).clear();
        }
        candidates.sort(java.util.Comparator
                .comparing(Post::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(Post::getId, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return candidates.size() <= limit ? candidates : new ArrayList<>(candidates.subList(0, limit));
    }

}
