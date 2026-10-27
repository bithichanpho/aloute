package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

@lombok.Data
@PgDocument(collection = "follow_requests")
public class FollowRequest {
    @org.springframework.data.annotation.Id private String id;
    private Long followerId;
    private Long targetId;
    private java.time.Instant createdAt = java.time.Instant.now();
    private java.time.Instant seenAt;
}
