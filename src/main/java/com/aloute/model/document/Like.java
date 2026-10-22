package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;

@PgDocument(collection = "likes")
public class Like {

    @Id private String id;

    private String postId;
    private String userId;
    private LocalDateTime createdAt;

    private String reactionType;

    public Like() {
        this.createdAt = LocalDateTime.now();
    }

    public Like(String postId, String userId) {
        this.postId = postId;
        this.userId = userId;
        this.reactionType = "LIKE";
        this.createdAt = LocalDateTime.now();
    }

    public Like(String postId, String userId, String reactionType) {
        this.postId = postId;
        this.userId = userId;
        this.reactionType = reactionType == null ? "LIKE" : reactionType;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getReactionType() {
        return reactionType == null ? "LIKE" : reactionType;
    }

    public void setReactionType(String reactionType) {
        this.reactionType = reactionType;
    }
}
