package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResendVerificationRequest {

    @NotBlank(message = "Vui lòng nhập email hoặc tên đăng nhập")
    @Size(max = 254, message = "Email hoặc tên đăng nhập quá dài")
    private String identifier;

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }
}
