package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;

import com.aloute.validation.StrongPassword;

public class ChangePasswordRequest {

    @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại")
    private String currentPassword;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @StrongPassword
    private String newPassword;

    @jakarta.validation.constraints.Size(max = 72)
    private String confirmNewPassword;

    public String getConfirmNewPassword() { return confirmNewPassword; }
    public void setConfirmNewPassword(String value) { confirmNewPassword = value; }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
