package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;

@PgDocument(collection = "reposts")
public class Repost {

    @Id private String id;

    private String userId;

    private String postId;

    private String content;

    private LocalDateTime createdAt;

    public Repost() {
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
