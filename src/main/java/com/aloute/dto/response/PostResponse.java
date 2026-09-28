package com.aloute.dto.response;

import java.time.LocalDateTime;

public class PostResponse {
    @lombok.Getter @lombok.Setter private com.aloute.model.document.PostVisibility visibility;

    private String id;
    private Long userId;
    private String username;
    private String userAvatarUrl;
    private String content;
    private String imageUrl;
    private String videoUrl;
    private int likeCount;
    private int commentCount;
    private boolean likedByCurrentUser;
    private LocalDateTime createdAt;

    private java.util.Map<String, Integer> reactionCounts = new java.util.HashMap<>();
    private String currentUserReaction;

    private int repostCount;
    private boolean repostedByCurrentUser;

    private String interactionPostId;

    private RepostOrigin repostOf;

    private java.util.List<RepostActor> recentReposters = new java.util.ArrayList<>();

    private java.util.List<RepostActor> otherReposters = new java.util.ArrayList<>();

    private String timeAgo;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserAvatarUrl() {
        return userAvatarUrl;
    }

    public void setUserAvatarUrl(String userAvatarUrl) {
        this.userAvatarUrl = userAvatarUrl;
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

    public boolean isLikedByCurrentUser() {
        return likedByCurrentUser;
    }

    public void setLikedByCurrentUser(boolean likedByCurrentUser) {
        this.likedByCurrentUser = likedByCurrentUser;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTimeAgo() {
        return timeAgo;
    }

    public void setTimeAgo(String timeAgo) {
        this.timeAgo = timeAgo;
    }

    public java.util.Map<String, Integer> getReactionCounts() {
        return reactionCounts;
    }

    public void setReactionCounts(java.util.Map<String, Integer> reactionCounts) {
        this.reactionCounts = reactionCounts;
    }

    public String getCurrentUserReaction() {
        return currentUserReaction;
    }

    public void setCurrentUserReaction(String currentUserReaction) {
        this.currentUserReaction = currentUserReaction;
    }

    public int getRepostCount() {
        return repostCount;
    }

    public void setRepostCount(int repostCount) {
        this.repostCount = repostCount;
    }

    public boolean isRepostedByCurrentUser() {
        return repostedByCurrentUser;
    }

    public void setRepostedByCurrentUser(boolean repostedByCurrentUser) {
        this.repostedByCurrentUser = repostedByCurrentUser;
    }

    public String getInteractionPostId() {
        return interactionPostId;
    }

    public void setInteractionPostId(String interactionPostId) {
        this.interactionPostId = interactionPostId;
    }

    public RepostOrigin getRepostOf() {
        return repostOf;
    }

    public void setRepostOf(RepostOrigin repostOf) {
        this.repostOf = repostOf;
    }

    public java.util.List<RepostActor> getRecentReposters() {
        return recentReposters;
    }

    public void setRecentReposters(java.util.List<RepostActor> recentReposters) {
        this.recentReposters =
                recentReposters != null ? recentReposters : new java.util.ArrayList<>();
    }

    public java.util.List<RepostActor> getOtherReposters() {
        return otherReposters;
    }

    public void setOtherReposters(java.util.List<RepostActor> otherReposters) {
        this.otherReposters = otherReposters != null ? otherReposters : new java.util.ArrayList<>();
    }

    public static class RepostActor {
        private Long userId;
        private String username;
        private String userAvatarUrl;
        private LocalDateTime createdAt;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getUserAvatarUrl() {
            return userAvatarUrl;
        }

        public void setUserAvatarUrl(String userAvatarUrl) {
            this.userAvatarUrl = userAvatarUrl;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static class RepostOrigin {
        private String postId;
        private boolean deleted;
        private Long userId;
        private String username;
        private String userAvatarUrl;
        private String content;
        private String imageUrl;
        private String videoUrl;
        private String timeAgo;

        private int repostCount;

        public String getPostId() {
            return postId;
        }

        public void setPostId(String postId) {
            this.postId = postId;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getUserAvatarUrl() {
            return userAvatarUrl;
        }

        public void setUserAvatarUrl(String userAvatarUrl) {
            this.userAvatarUrl = userAvatarUrl;
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

        public String getTimeAgo() {
            return timeAgo;
        }

        public void setTimeAgo(String timeAgo) {
            this.timeAgo = timeAgo;
        }

        public int getRepostCount() {
            return repostCount;
        }

        public void setRepostCount(int repostCount) {
            this.repostCount = repostCount;
        }
    }
}
