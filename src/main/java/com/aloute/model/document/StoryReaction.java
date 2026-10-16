package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class StoryReaction {
    @org.springframework.data.annotation.Id private String id;
    private String storyId;
    private Long userId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
