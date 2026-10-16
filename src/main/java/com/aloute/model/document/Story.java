package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class Story {
    @org.springframework.data.annotation.Id private String id;
    private Long userId;
    private String content;
    private String imageUrl;
    private String videoUrl;
    private java.time.Instant expiresAt;
    private java.time.Instant createdAt = java.time.Instant.now();
}