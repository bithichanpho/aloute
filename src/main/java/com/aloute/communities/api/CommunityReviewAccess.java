package com.aloute.communities.api;

/** Current manager authorization for delayed post-review notifications. */
public interface CommunityReviewAccess {
    boolean mayReviewPosts(String communityId, Long userId);
}
