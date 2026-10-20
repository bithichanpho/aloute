package com.aloute.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public class GroupResponse {

    private Long id;
    private String name;
    private String avatarUrl;
    private Long createdBy;
    private LocalDateTime createdAt;
    private List<GroupMemberDto> members;
    private int memberCount;

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

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<GroupMemberDto> getMembers() {
        return members;
    }

    public void setMembers(List<GroupMemberDto> members) {
        this.members = members;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = Math.max(0, memberCount);
    }
}
