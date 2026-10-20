package com.aloute.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** Durable intent for a notification that crosses a transaction or external delivery boundary. */
@Entity
@Table(
        name = "notification_outbox",
        indexes = @Index(
                name = "idx_notification_outbox_status_due",
                columnList = "status,next_attempt_at,created_at"))
public class NotificationOutboxEvent {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DEAD = "DEAD";
    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "notification_type", nullable = false, length = 30)
    private String notificationType;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(nullable = false, length = 255)
    private String content;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public String getId() { return id; }
    public Long getReceiverId() { return receiverId; }
    public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }
    public String getNotificationType() { return notificationType; }
    public void setNotificationType(String notificationType) { this.notificationType = notificationType; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
