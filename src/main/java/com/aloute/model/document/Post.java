package com.aloute.model.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "posts")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "posts_created_desc",
            def = "{'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "posts_user_created",
            def = "{'userId': 1, 'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "posts_community_created",
            def = "{'communityId': 1, 'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(
            name = "posts_repost_origin_created",
            def = "{'repostOfId': 1, 'createdAt': -1}")
})
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

    private int likeCount = 0;
    private int commentCount = 0;
    private int repostCount = 0;

    private String repostOfId;

    private java.util.Map<String, Integer> reactionCounts = new java.util.HashMap<>();

    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;
    private double toxicityScore = 0.0;

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
