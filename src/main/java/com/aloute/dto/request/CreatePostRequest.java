package com.aloute.dto.request;

import com.aloute.model.document.PostVisibility;

import com.aloute.validation.HttpUrl;
import com.aloute.validation.AtLeastOneNotBlank;

import jakarta.validation.constraints.Size;

@AtLeastOneNotBlank(
        fields = {"content", "imageUrl", "videoUrl"},
        message = "Bài viết cần có nội dung, hình ảnh hoặc video")
public class CreatePostRequest {

    @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự")
    private String content;

    @jakarta.validation.constraints.NotNull
    private PostVisibility visibility =
            PostVisibility.PUBLIC;

    @Size(max = 2048, message = "URL hình ảnh tối đa 2048 ký tự")
    @HttpUrl
    private String imageUrl;

    @Size(max = 2048, message = "URL video tối đa 2048 ký tự")
    @HttpUrl
    private String videoUrl;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public PostVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(PostVisibility visibility) {
        this.visibility = visibility;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }
}
