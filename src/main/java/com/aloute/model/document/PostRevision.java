package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class PostRevision {
    @org.springframework.data.annotation.Id private String id;
    private String postId;
    private String content;
    private String imageUrl;
    private String videoUrl;
    private String visibility;
    private Long editorId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
