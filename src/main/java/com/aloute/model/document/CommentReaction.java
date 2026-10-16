package com.aloute.model.document;

@lombok.Data
@org.springframework.data.mongodb.core.mapping.Document
@org.springframework.data.mongodb.core.index.CompoundIndex(
        name = "comment_reaction_unique",
        def = "{'commentId': 1, 'userId': 1}",
        unique = true)
public class CommentReaction {
    @org.springframework.data.annotation.Id private String id;
    private String commentId;
    private Long userId;
    private String type;
    private java.time.Instant createdAt = java.time.Instant.now();
}
