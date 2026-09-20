package com.aloute.dto.response;

import java.time.LocalDateTime;

/**
 * Thông tin 1 hội nhóm dành cho giao diện. Các field {@code member}/{@code admin} là theo GÓC NHÌN
 * của người đang xem (dùng để tô nút "Tham gia"/"Đã tham gia" và huy hiệu "Quản trị").
 */
public class CommunityResponse {

    private Long id;
    private String name;
    private String description;
    private String avatarUrl;
    private String coverUrl;
    private String privacy; // PUBLIC | PRIVATE
    private int memberCount;
    private LocalDateTime createdAt;

    /** Người đang xem đã là thành viên hay chưa. */
    private boolean member;

    /** Người đang xem có phải quản trị viên của hội nhóm hay không. */
    private boolean admin;

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

    public boolean isMember() {
        return member;
    }

    public void setMember(boolean member) {
        this.member = member;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }
}