package com.aloute.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateCommentRequest {

    @NotBlank(message = "Nội dung bình luận không được để trống")
    @Size(max = 500, message = "Bình luận tối đa 500 ký tự")
    private String content;

    /** Không truyền (hoặc null) = bình luận gốc vào bài viết. Truyền id 1 comment khác = reply lồng vào comment đó. */
    private String parentCommentId;

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