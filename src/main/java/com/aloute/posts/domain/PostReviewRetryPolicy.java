package com.aloute.posts.domain;

import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import java.util.Set;

/** Infrastructure outages may be retried; content decisions still require administrator review. */
public final class PostReviewRetryPolicy {
    private static final Set<String> OUTAGES = Set.of("TEXT_SERVICE_UNAVAILABLE", "MEDIA_SERVICE_UNAVAILABLE");
    private PostReviewRetryPolicy() {}

    public static boolean canRetry(Post post) {
        return post != null && post.getRepostOfId() == null
                && post.getModerationStatus() == ModerationStatus.FLAGGED
                && post.getModerationFailureReason() != null
                && OUTAGES.contains(post.getModerationFailureReason());
    }
}
