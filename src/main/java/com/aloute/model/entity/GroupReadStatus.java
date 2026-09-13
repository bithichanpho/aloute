package com.aloute.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Lưu mốc thời gian đọc gần nhất của mỗi user trong mỗi nhóm, dùng để tính unreadCount. */
@Entity
@Table(name = "group_read_status", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"group_id", "user_id"})
})
public class GroupReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_read_at", nullable = false)
    private LocalDateTime lastReadAt;

    public GroupReadStatus() {
    }

    public GroupReadStatus(Long groupId, Long userId) {
        this.groupId = groupId;
        this.userId = userId;
        this.lastReadAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(LocalDateTime lastReadAt) {
        this.lastReadAt = lastReadAt;
    }
}