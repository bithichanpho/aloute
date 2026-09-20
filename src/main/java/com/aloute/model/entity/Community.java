package com.aloute.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Hội nhóm (Community) kiểu Facebook Group: 1 không gian có tên/mô tả riêng, thành viên
 * có thể đăng bài trong đó, khác với {@link ChatGroup} vốn chỉ dùng để nhắn tin nhóm.
 */
@Entity
@Table(name = "communities", indexes = { @Index(name = "idx_communities_created_by", columnList = "created_by"), @Index(name = "idx_communities_privacy_members", columnList = "privacy, member_count") })
public class Community {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    /** PUBLIC: ai cũng thấy trong danh sách khám phá và xem được bài viết. PRIVATE: chỉ thành viên xem được bài viết. */
    @Column(nullable = false, length = 20)
    private String privacy = "PUBLIC";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    /** Đếm số thành viên — cập nhật khi join/leave, tránh phải COUNT(*) mỗi lần hiển thị danh sách. */
    @Column(name = "member_count", nullable = false)
    private int memberCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Community() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getPrivacy() {
        return privacy;
    }

    public void setPrivacy(String privacy) {
        this.privacy = privacy;
    }

    public User getCreator() { return creator; }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}