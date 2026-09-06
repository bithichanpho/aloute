package com.aloute.dto.response;

import java.time.LocalDateTime;

public class ConversationResponse {

    private Long otherUserId;
    private String otherUsername;
    private String otherFullName;
    private String otherAvatarUrl;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
    private long unreadCount;

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
}