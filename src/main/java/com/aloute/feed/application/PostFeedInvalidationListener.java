package com.aloute.feed.application;

import com.aloute.feed.api.FeedInvalidation;
import com.aloute.posts.api.PostChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Synchronous best-effort invalidation; Redis TTL remains the fallback after an outage/crash. */
@Component
public class PostFeedInvalidationListener {
    private final FeedInvalidation cache;

    public PostFeedInvalidationListener(FeedInvalidation cache) { this.cache = cache; }

    @EventListener
    public void onPostChanged(PostChanged event) {
        if (event.invalidateAll()) cache.invalidateAll();
        cache.invalidateViewer(event.actorId());
    }
}
