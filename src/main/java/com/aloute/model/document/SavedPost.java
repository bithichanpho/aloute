package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

@lombok.Data
@PgDocument(collection = "saved_posts")
public class SavedPost {
    @org.springframework.data.annotation.Id private String id;
    private Long userId;
    private String postId;
    private java.time.Instant createdAt = java.time.Instant.now();
}
