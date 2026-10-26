package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {

    @NotBlank(message = "Tên đăng nhập hoặc email không được để trống")
    @Size(max = 254, message = "Tên đăng nhập hoặc email quá dài")
    private String username;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(max = 72, message = "Mật khẩu tối đa 72 ký tự")
    private String password;

    @lombok.Getter @lombok.Setter @Size(max = 64, message = "Mã xác thực quá dài") private String code;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
