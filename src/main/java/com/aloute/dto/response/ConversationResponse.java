package com.aloute.dto.response;

import java.time.LocalDateTime;

public class ConversationResponse {

    private boolean group;
    private Long groupId;
    private String groupName;
    private int memberCount;

    private Long otherUserId;
    private String otherUsername;
    private String otherFullName;

    private String otherAvatarUrl;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
    private long unreadCount;
    private String lastMessageTimeAgo;
    private boolean online;
    private boolean hasConversation = true;

    public boolean isGroup() {
        return group;
    }

    public void setGroup(boolean group) {
        this.group = group;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public Long getOtherUserId() {
        return otherUserId;
    }

    public void setOtherUserId(Long otherUserId) {
        this.otherUserId = otherUserId;
    }

    public String getOtherUsername() {
        return otherUsername;
    }

    public void setOtherUsername(String otherUsername) {
        this.otherUsername = otherUsername;
    }

    public String getOtherFullName() {
        return otherFullName;
    }

    public void setOtherFullName(String otherFullName) {
        this.otherFullName = otherFullName;
    }

    public String getOtherAvatarUrl() {
        return otherAvatarUrl;
    }

    public void setOtherAvatarUrl(String otherAvatarUrl) {
        this.otherAvatarUrl = otherAvatarUrl;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public long getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(long unreadCount) {
        this.unreadCount = unreadCount;
    }

    public String getLastMessageTimeAgo() {
        return lastMessageTimeAgo;
    }

    public void setLastMessageTimeAgo(String lastMessageTimeAgo) {
        this.lastMessageTimeAgo = lastMessageTimeAgo;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public boolean isHasConversation() {
        return hasConversation;
    }

    public void setHasConversation(boolean hasConversation) {
        this.hasConversation = hasConversation;
    }
}
