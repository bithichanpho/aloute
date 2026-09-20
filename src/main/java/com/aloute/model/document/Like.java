package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "likes")
@CompoundIndex(name = "post_user_unique", def = "{'postId': 1, 'userId': 1}", unique = true)
@CompoundIndex(name = "like_user_created", def = "{'userId': 1, 'createdAt': -1}")
public class Like {

    @Id
    private String id;

    private String postId;
    private String userId;
    private LocalDateTime createdAt;

    /**
     * Loại reaction: LIKE, LOVE, HAHA, WOW, SAD, ANGRY (kiểu Facebook).
     * Các bản ghi TẠO TRƯỚC khi có tính năng này sẽ có giá trị null trong DB —
     * {@link #getReactionType()} tự trả về "LIKE" cho trường hợp đó để tương thích ngược,
     * không cần chạy migration dữ liệu cũ.
     */
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