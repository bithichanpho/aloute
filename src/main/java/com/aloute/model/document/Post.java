package com.aloute.model.document;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "posts")
@org.springframework.data.mongodb.core.index.CompoundIndexes({
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "posts_created_desc", def = "{'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "posts_user_created", def = "{'userId': 1, 'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "posts_community_created", def = "{'communityId': 1, 'createdAt': -1}"),
    @org.springframework.data.mongodb.core.index.CompoundIndex(name = "posts_repost_origin_created", def = "{'repostOfId': 1, 'createdAt': -1}")
})
public class Post {

    @Id
    private String id;

    private String userId;          // tham chiếu tới users.id (SQL), lưu dạng String
    private String content;
    private String imageUrl;        // nullable, URL trả về từ Cloudinary

    /** Null nếu là bài viết trên feed cá nhân bình thường; khác null nếu đăng trong 1 hội nhóm. */
    private String communityId;

    private int likeCount = 0;
    private int commentCount = 0;
    private int repostCount = 0;

    /**
     * Null nếu đây là bài viết bình thường. Nếu khác null, đây là một lượt "đăng lại"
     * (repost) và giá trị này trỏ tới id bài viết GỐC. "content" của bài repost (nếu có)
     * đóng vai trò như lời bình luận khi chia sẻ lại (giống "Quote" của Threads/X).
     */
    private String repostOfId;

    /** Đếm số phản ứng (reaction) theo từng loại emoji, vd {"LIKE": 5, "LOVE": 2}. Tổng của map này = likeCount. */
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