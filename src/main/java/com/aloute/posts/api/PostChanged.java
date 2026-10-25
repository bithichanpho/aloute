package com.aloute.posts.api;

import java.time.Instant;
import java.util.UUID;

/** In-process cache hint only. Contains no post body/media and makes no durable-delivery promise. */
public record PostChanged(String eventId, String type, int version, Instant occurredAt,
        String aggregateId, Long actorId, boolean invalidateAll) {
    public static PostChanged edited(String postId, Long actorId, boolean invalidateAll) {
        return new PostChanged(UUID.randomUUID().toString(), "POST_EDITED", 1,
                Instant.now(), postId, actorId, invalidateAll);
    }
}
