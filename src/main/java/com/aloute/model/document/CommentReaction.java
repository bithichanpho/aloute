package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

@lombok.Data
@PgDocument(collection = "comment_reactions")
public class CommentReaction {
    @org.springframework.data.annotation.Id private String id;
    private String commentId;
    private Long userId;
    private String type;
    private java.time.Instant createdAt = java.time.Instant.now();
}
