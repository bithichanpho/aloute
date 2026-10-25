package com.aloute.social.api;

import java.util.List;

/** Keyset read of owned follow edges; callers never load the whole social graph. */
public interface FollowedAccounts {
    /** Published SQL read interface owned by Social; columns: viewer_id bigint, value text. */
    String READ_VIEW = "aloute_read_social_following_authors";
    List<Long> followedIdsAfter(Long viewerId, Long afterId, int limit);

    default boolean hasFollowing(Long viewerId) {
        return !followedIdsAfter(viewerId, 0L, 1).isEmpty();
    }
}
