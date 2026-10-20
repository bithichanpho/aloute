package com.aloute.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "account_admin_audit", indexes = {
        @Index(name = "idx_account_admin_audit_user_created",
                columnList = "user_id, created_at")
})
public class AccountAdminAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AccountAdminAudit() {}

    public AccountAdminAudit(Long userId, Long actorId, String action, String reason) {
        this.userId = userId;
        this.actorId = actorId;
        this.action = action;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
