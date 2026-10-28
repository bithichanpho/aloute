package com.aloute.dto.request;

import jakarta.validation.constraints.Pattern;

public class ReactionRequest {

    // null expresses removal; non-null values must still be a supported reaction.
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
