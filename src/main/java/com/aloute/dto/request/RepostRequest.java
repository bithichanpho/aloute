package com.aloute.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Body (không bắt buộc) khi đăng lại 1 bài viết. Khác {@link CreatePostRequest}: content
 * ở đây KHÔNG @NotBlank vì repost thuần (không kèm bình luận) là hợp lệ — giống nút
 * "Repost" của Threads/X, chỉ khi muốn thêm lời bình mới cần nhập content.
 */
public class RepostRequest {

    @Size(max = 500, message = "Lời bình khi đăng lại tối đa 500 ký tự")
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}