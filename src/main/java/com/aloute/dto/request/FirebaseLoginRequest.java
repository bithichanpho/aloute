package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class FirebaseLoginRequest {

    @NotBlank(message = "Thiếu Firebase ID token")
    @Size(max = 8192, message = "Firebase ID token không hợp lệ")
    private String idToken;

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }
}
