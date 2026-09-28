package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class UserBlock {
    @org.springframework.data.annotation.Id private String id;
    private Long ownerId;
    private Long targetId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
