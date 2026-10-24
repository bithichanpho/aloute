package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class FirebaseLoginRequest {

    @NotBlank(message = "Thiếu Firebase ID token")
    @Size(max = 8192, message = "Firebase ID token không hợp lệ")
    private String idToken;

    @Size(max = 64, message = "Mã xác thực quá dài")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }
}
