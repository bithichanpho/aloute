package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "comments")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "comments_post_created", def = "{'postId': 1, 'createdAt': 1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "comments_parent_created", def = "{'parentCommentId': 1, 'createdAt': 1}")
})
public class Comment {

    @Id
    private String id;

    private String postId;
    private String userId;
    private String content;

    /** null = bình luận gốc (trả lời trực tiếp bài viết). Có giá trị = đang trả lời một bình luận khác (reply lồng nhau kiểu Threads). */
    private String parentCommentId;

    /** Số lượt reply trực tiếp vào bình luận này (denormalized, cập nhật bởi CommentService). */
    private int replyCount = 0;

    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;

    private LocalDateTime createdAt;

    public Comment() {
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(String parentCommentId) {
        this.parentCommentId = parentCommentId;
    }

    public int getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(int replyCount) {
        this.replyCount = replyCount;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public void setModerationStatus(ModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}