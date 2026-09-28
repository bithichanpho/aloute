package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class SavedPost {
    @org.springframework.data.annotation.Id private String id;
    private Long userId;
    private String postId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
