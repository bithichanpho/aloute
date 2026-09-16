package com.aloute.dto.response;

public class UserProfileDto {

    private Long id;
    private String username;
    private String fullName;
    private String avatarUrl;
    private String coverUrl;
    private String bio;
    private long followerCount;
    private long followingCount;
    private long closeFriendCount;
    private boolean isFollowedByCurrentUser;
    private boolean mutualFriend; // true nếu 2 người đã follow lẫn nhau ("bạn bè")
    private String closeFriendStatus = "NONE"; // NONE | PENDING_SENT | PENDING_RECEIVED | ACCEPTED
    private boolean privateAccount;  // chủ tài khoản bật chế độ riêng tư
    private boolean canMessage;      // người đang xem có được phép nhắn tin không
    private String suggestionReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public long getFollowerCount() {
        return followerCount;
    }

    public void setFollowerCount(long followerCount) {
        this.followerCount = followerCount;
    }

    public long getFollowingCount() {
        return followingCount;
    }

    public void setFollowingCount(long followingCount) {
        this.followingCount = followingCount;
    }

    public long getCloseFriendCount() {
        return closeFriendCount;
    }

    public void setCloseFriendCount(long closeFriendCount) {
        this.closeFriendCount = closeFriendCount;
    }

    public boolean isFollowedByCurrentUser() {
        return isFollowedByCurrentUser;
    }

    public void setFollowedByCurrentUser(boolean followedByCurrentUser) {
        isFollowedByCurrentUser = followedByCurrentUser;
    }

    public boolean isMutualFriend() {
        return mutualFriend;
    }

    public void setMutualFriend(boolean mutualFriend) {
        this.mutualFriend = mutualFriend;
    }

    public String getCloseFriendStatus() {
        return closeFriendStatus;
    }

    public void setCloseFriendStatus(String closeFriendStatus) {
        this.closeFriendStatus = closeFriendStatus;
    }

    public boolean isPrivateAccount() {
        return privateAccount;
    }

    public void setPrivateAccount(boolean privateAccount) {
        this.privateAccount = privateAccount;
    }

    public boolean isCanMessage() {
        return canMessage;
    }

    public void setCanMessage(boolean canMessage) {
        this.canMessage = canMessage;
    }

    public String getSuggestionReason() {
        return suggestionReason;
    }

    public void setSuggestionReason(String suggestionReason) {
        this.suggestionReason = suggestionReason;
    }
}