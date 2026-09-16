package com.aloute.dto.request;

import jakarta.validation.constraints.Size;

public class UpdateProfileRequest {

    private String fullName;

    @Size(max = 300, message = "Giới thiệu bản thân tối đa 300 ký tự")
    private String bio;

    /** null = khong doi; true/false = bat/tat che do tai khoan rieng tu. */
    private Boolean privateAccount;

    public Boolean getPrivateAccount() {
        return privateAccount;
    }

    public void setPrivateAccount(Boolean privateAccount) {
        this.privateAccount = privateAccount;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }
}
