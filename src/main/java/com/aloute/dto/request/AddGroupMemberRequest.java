package com.aloute.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class AddGroupMemberRequest {

    @NotNull(message = "Thiếu userId cần thêm vào nhóm")
    @Positive(message = "userId phải lớn hơn 0")
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
