package com.aloute.posts.api;

/** Hands an already-persisted PENDING post to the existing lease-based recovery mechanism. */
public interface PostReviewDispatch {
    void submit(String postId);
}
