package com.aloute.model.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "friends",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"follower_id", "followed_id"})
        },
        // Hai chiều tra cứu nóng nhất: "tôi đang follow ai" và "ai đang follow tôi".
        // Thiếu index, mỗi lần dựng feed/gợi ý là một lần quét toàn bảng.
        indexes = {
                @Index(name = "idx_friends_follower", columnList = "follower_id"),
                @Index(name = "idx_friends_followed", columnList = "followed_id")
        })
public class Friend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "follower_id", nullable = false)
    private Long followerId;   // người thực hiện follow

    @Column(name = "followed_id", nullable = false)
    private Long followedId;   // người được follow

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Friend() {
        this.createdAt = LocalDateTime.now();
    }

    public Friend(Long followerId, Long followedId) {
        this.followerId = followerId;
        this.followedId = followedId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFollowerId() {
        return followerId;
    }

    public void setFollowerId(Long followerId) {
        this.followerId = followerId;
    }

    public Long getFollowedId() {
        return followedId;
    }

    public void setFollowedId(Long followedId) {
        this.followedId = followedId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
