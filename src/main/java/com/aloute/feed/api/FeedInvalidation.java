package com.aloute.feed.api;

public interface FeedInvalidation {
    void invalidateViewer(Long viewerId);
    void invalidateAll();
}
