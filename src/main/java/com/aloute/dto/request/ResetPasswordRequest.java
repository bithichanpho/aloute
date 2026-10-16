package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;

import com.aloute.validation.StrongPassword;

public class ResetPasswordRequest {

    @NotBlank(message = "Thiếu mã đặt lại mật khẩu")
    private String token;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @StrongPassword
    private String newPassword;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
