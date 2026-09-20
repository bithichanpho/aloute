package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Quan he "user da dang lai bai viet". Day khong phai la mot Post moi,
 * nen khong xuat hien nhu mot item rieng tren feed.
 */
@Document(collection = "reposts")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @CompoundIndex(name = "repost_user_post_unique", def = "{'userId': 1, 'postId': 1}", unique = true),
    @CompoundIndex(name = "repost_post_created", def = "{'postId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "repost_user_created", def = "{'userId': 1, 'createdAt': -1}")
})
public class Repost {

    @Id
    private String id;

    /** PostgreSQL users.id, luu dang String de dong bo voi cac Mongo document hien tai. */
    private String userId;

    /** MongoDB posts._id cua bai goc. */
    private String postId;

    /** Reserve cho quote repost sau nay; repost thuong de null. */
    private String content;

    private LocalDateTime createdAt;

    public Repost() {
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
