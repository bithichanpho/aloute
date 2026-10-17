package com.aloute.dto.request;

import jakarta.validation.constraints.Size;

public class RepostRequest {

    @Size(max = 500, message = "Lời bình khi đăng lại tối đa 500 ký tự")
    private String content;

    /** Optional desired state. When supplied, repeating the request is idempotent. */
    private Boolean reposted;

    public Boolean getReposted() {
        return reposted;
    }

    public void setReposted(Boolean reposted) {
        this.reposted = reposted;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
