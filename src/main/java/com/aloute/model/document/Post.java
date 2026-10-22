package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;

@PgDocument(collection = "posts")
public class Post {

    @Id private String id;

    private String userId;
    private String content;

    @lombok.Getter @lombok.Setter
    private java.util.List<PostRevision> editHistory = new java.util.ArrayList<>();

    @lombok.Getter @lombok.Setter private PostVisibility visibility = PostVisibility.PUBLIC;

    @lombok.Getter @lombok.Setter
    private java.util.List<String> hashtags = new java.util.ArrayList<>();

    @lombok.Getter @lombok.Setter
    private java.util.List<String> mentions = new java.util.ArrayList<>();

    private String imageUrl;
    private String videoUrl;

    private String communityId;

    /** Null means APPROVED for posts created before community review was introduced. */
    private CommunityPostStatus communityPostStatus;

    private int likeCount = 0;
    private int commentCount = 0;
    private int repostCount = 0;

    /**
     * Denormalized preview used by feed responses. Keeping only three ids prevents a popular post
     * from forcing the feed to read every repost document just to render its banner.
     */
    private java.util.List<String> recentReposterIds = new java.util.ArrayList<>();

    private String repostOfId;

    private java.util.Map<String, Integer> reactionCounts = new java.util.HashMap<>();

    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;
    private double toxicityScore = 0.0;
    // PostgreSQL-backed lease allows retry/recovery after queue rejection or process restart.
    private LocalDateTime moderationLeaseUntil;
    private LocalDateTime moderationLastAttemptAt;
    private int moderationAttempts;
    /** Safe machine-readable reason when automatic moderation needs administrator review. */
    private String moderationFailureReason;
    private boolean notifyMentionsAfterModeration;

    /** Marks that lifecycle tombstones were durably queued for every media URL on a hidden post. */
    private LocalDateTime mediaCleanupQueuedAt;

    public LocalDateTime getModerationLeaseUntil() { return moderationLeaseUntil; }
    public void setModerationLeaseUntil(LocalDateTime value) { this.moderationLeaseUntil = value; }
    public LocalDateTime getModerationLastAttemptAt() { return moderationLastAttemptAt; }
    public void setModerationLastAttemptAt(LocalDateTime value) { this.moderationLastAttemptAt = value; }
    public int getModerationAttempts() { return moderationAttempts; }
    public void setModerationAttempts(int value) { this.moderationAttempts = Math.max(0, value); }
    public String getModerationFailureReason() { return moderationFailureReason; }
    public void setModerationFailureReason(String value) { this.moderationFailureReason = value; }

    public boolean isNotifyMentionsAfterModeration() {
        return notifyMentionsAfterModeration;
    }

    public void setNotifyMentionsAfterModeration(boolean notifyMentionsAfterModeration) {
        this.notifyMentionsAfterModeration = notifyMentionsAfterModeration;
    }

    public LocalDateTime getMediaCleanupQueuedAt() { return mediaCleanupQueuedAt; }
    public void setMediaCleanupQueuedAt(LocalDateTime value) { this.mediaCleanupQueuedAt = value; }

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Post() {
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

    public String getCommunityId() {
        return communityId;
    }

    public void setCommunityId(String communityId) {
        this.communityId = communityId;
    }

    public CommunityPostStatus getCommunityPostStatus() {
        return communityPostStatus;
    }

    public void setCommunityPostStatus(CommunityPostStatus communityPostStatus) {
        this.communityPostStatus = communityPostStatus;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public int getRepostCount() {
        return repostCount;
    }

    public void setRepostCount(int repostCount) {
        this.repostCount = repostCount;
    }

    public java.util.List<String> getRecentReposterIds() {
        return recentReposterIds;
    }

    public void setRecentReposterIds(java.util.List<String> recentReposterIds) {
        this.recentReposterIds = recentReposterIds == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(recentReposterIds);
    }

    public String getRepostOfId() {
        return repostOfId;
    }

    public void setRepostOfId(String repostOfId) {
        this.repostOfId = repostOfId;
    }

    public java.util.Map<String, Integer> getReactionCounts() {
        return reactionCounts;
    }

    public void setReactionCounts(java.util.Map<String, Integer> reactionCounts) {
        this.reactionCounts = reactionCounts;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public void setModerationStatus(ModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public double getToxicityScore() {
        return toxicityScore;
    }

    public void setToxicityScore(double toxicityScore) {
        this.toxicityScore = toxicityScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
