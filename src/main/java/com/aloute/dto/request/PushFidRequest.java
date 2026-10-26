package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PushFidRequest {

    @NotBlank(message = "Thiếu Firebase Installation ID")
    @Size(max = 255, message = "Firebase Installation ID không hợp lệ")
    private String fid;

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
    }
}
