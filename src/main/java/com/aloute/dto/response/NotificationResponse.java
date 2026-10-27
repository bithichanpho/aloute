package com.aloute.dto.response;

import java.time.LocalDateTime;

public class NotificationResponse {

    private Long id;
    @lombok.Getter @lombok.Setter private Long senderId;
    @lombok.Getter @lombok.Setter private String senderUsername;
    @lombok.Getter @lombok.Setter private String senderFullName;
    @lombok.Getter @lombok.Setter private String senderAvatarUrl;
    @lombok.Getter @lombok.Setter private String followRequestId;
    private String type;
    private String content;
    private String referenceId;
    private boolean read;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
