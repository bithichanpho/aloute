package com.aloute.dto.request;

import jakarta.validation.constraints.NotNull;

public class AddGroupMemberRequest {

    @NotNull(message = "Thiếu userId cần thêm vào nhóm")
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}