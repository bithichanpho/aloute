package com.aloute.dto.response;

import java.time.Instant;

/** Existing request fields plus public sender labels, never credentials. */
public record FollowRequestResponse(String id, Long followerId, Long targetId, Instant createdAt,
        String followerUsername, String followerFullName, String followerAvatarUrl) {
    public FollowRequestResponse(String id, Long followerId, Long targetId, Instant createdAt,
            String followerUsername, String followerFullName) {
        this(id, followerId, targetId, createdAt, followerUsername, followerFullName, null);
    }
}
