package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class ReactionRequest {

    @NotBlank(message = "Vui lòng chọn cảm xúc")
    @Pattern(
            regexp = "(?i)LIKE|LOVE|HAHA|WOW|SAD|ANGRY",
            message = "Cảm xúc không hợp lệ")
    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
