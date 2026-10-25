package com.aloute.posts.api;

import java.time.Instant;

/** Same revision fields as the existing HTTP response, without exposing a persistence object. */
public record PostRevisionView(String id, String postId, String content, String imageUrl,
        String videoUrl, String visibility, Long editorId, Instant createdAt) {}
