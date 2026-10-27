package com.aloute.posts.api;

import java.util.Optional;

/** Minimal post lookup for notification delivery; no post body or persistence type crosses modules. */
public interface PostNotificationLookup {
    Optional<Long> ownerOf(String postId);
    java.util.Map<String, Long> ownersOf(java.util.Collection<String> postIds);
    boolean isReadable(String postId, Long viewerId);
    boolean canReviewCommunityPost(String postId, Long viewerId);
}
