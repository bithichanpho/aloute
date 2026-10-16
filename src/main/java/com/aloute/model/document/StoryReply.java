package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class StoryReply {
    @org.springframework.data.annotation.Id private String id;
    private String storyId;
    private Long senderId;
    private Long ownerId;
    private String content;
    private java.time.Instant createdAt = java.time.Instant.now();
}
