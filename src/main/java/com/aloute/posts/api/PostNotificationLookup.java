package com.aloute.posts.api;

import java.util.Optional;

/** Minimal post lookup for notification delivery; no post body or persistence type crosses modules. */
public interface PostNotificationLookup {
    Optional<Long> ownerOf(String postId);
    boolean isReadable(String postId, Long viewerId);
    boolean canReviewCommunityPost(String postId, Long viewerId);
}
