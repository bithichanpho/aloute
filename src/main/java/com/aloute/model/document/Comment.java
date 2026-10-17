package com.aloute.model.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "comments")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "comments_post_created",
            def = "{'postId': 1, 'createdAt': 1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "comments_parent_created",
            def = "{'parentCommentId': 1, 'createdAt': 1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "comments_user_created",
            def = "{'userId': 1, 'createdAt': -1}")
})
public class Comment {

    @Id private String id;

    private String postId;
    private String userId;
    private String content;

    private String parentCommentId;

    private int replyCount = 0;

    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;
    private LocalDateTime moderationLeaseUntil;
    private LocalDateTime moderationLastAttemptAt;
    private int moderationAttempts;
    /** Safe machine-readable reason when automatic moderation needs administrator review. */
    private String moderationFailureReason;
    // Defaults true for legacy documents whose counter increments predate this field.
    private boolean moderationCountersApplied = true;
    /** Only newly-created pending comments send a notification after moderator approval. */
    private boolean notifyAfterModeration;

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

    public LocalDateTime getModerationLeaseUntil() { return moderationLeaseUntil; }
    public void setModerationLeaseUntil(LocalDateTime value) { this.moderationLeaseUntil = value; }
    public LocalDateTime getModerationLastAttemptAt() { return moderationLastAttemptAt; }
    public void setModerationLastAttemptAt(LocalDateTime value) { this.moderationLastAttemptAt = value; }
    public int getModerationAttempts() { return moderationAttempts; }
    public void setModerationAttempts(int value) { this.moderationAttempts = Math.max(0, value); }
    public String getModerationFailureReason() { return moderationFailureReason; }
    public void setModerationFailureReason(String value) { this.moderationFailureReason = value; }
    public boolean isModerationCountersApplied() { return moderationCountersApplied; }
    public void setModerationCountersApplied(boolean value) { this.moderationCountersApplied = value; }
    public boolean isNotifyAfterModeration() { return notifyAfterModeration; }
    public void setNotifyAfterModeration(boolean value) { this.notifyAfterModeration = value; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
