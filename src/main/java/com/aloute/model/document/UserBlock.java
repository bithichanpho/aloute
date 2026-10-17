package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
public class UserBlock {
    @org.springframework.data.annotation.Id private String id;
    private Long ownerId;
    private Long targetId;
    /** Internal reconciliation state; do not expose it in API responses. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean relationshipsSynced = false;
    private java.time.Instant createdAt = java.time.Instant.now();
}
