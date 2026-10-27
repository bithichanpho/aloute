package com.aloute.posts.api;

import com.aloute.dto.response.PostResponse;
import java.time.LocalDateTime;
import java.util.List;

/** Posts owns SQL, object authorization and card assembly; feed owns traversal. */
public interface FeedPostReader {
    record Position(LocalDateTime createdAt, String id) {}

    /** A null card means the candidate was consumed but is not visible to this viewer. */
    record Candidate(Position position, PostResponse card) {}

    List<Candidate> readBatch(Long viewerId, List<String> followed, boolean streamFollowing,
            boolean followingOnly, String normalizedTag, Position before, int limit);

    /** Adds the reposters visible to {@code viewerId} (self + followed accounts). */
    void attachRecentReposters(Long viewerId, List<PostResponse> cards);

    /** Reload at most 30 cached IDs with current object authorization and nested content. */
    List<PostResponse> refreshCards(Long viewerId, List<String> postIds);

    /** Rechecks current follows as well as object visibility for cached Following pages. */
    default List<PostResponse> refreshFollowingCards(Long viewerId, List<String> postIds) {
        // Compatibility implementations fail closed until they implement the live scope check.
        return List.of();
    }
}
