package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

@lombok.Data
@PgDocument(collection = "user_blocks")
public class UserBlock {
    @org.springframework.data.annotation.Id private String id;
    private Long ownerId;
    private Long targetId;
    /** Internal reconciliation state; do not expose it in API responses. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean relationshipsSynced = false;
    private java.time.Instant createdAt = java.time.Instant.now();
}
