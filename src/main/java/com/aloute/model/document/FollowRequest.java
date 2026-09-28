package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class FollowRequest {
    @org.springframework.data.annotation.Id private String id;
    private Long followerId;
    private Long targetId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
