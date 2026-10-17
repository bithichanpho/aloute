package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateCommentRequest {

    @NotBlank(message = "Nội dung bình luận không được để trống")
    @Size(max = 500, message = "Bình luận tối đa 500 ký tự")
    private String content;

    @Size(max = 100, message = "Mã bình luận cha không hợp lệ")
    private String parentCommentId;

    /** Client-generated stable key reused when the same create request is retried. */
    @Size(max = 80, message = "Mã yêu cầu bình luận không hợp lệ")
    private String clientRequestId;

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(String parentCommentId) {
        this.parentCommentId = parentCommentId;
    }
}
