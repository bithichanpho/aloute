package com.aloute.dto.request;

import com.aloute.model.document.PostVisibility;
import com.aloute.validation.HttpUrl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Omitted edit fields preserve stored values; explicit null media still removes it. */
public class EditPostRequest {
    @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự")
    private String content;
    @NotNull
    private PostVisibility visibility = PostVisibility.PUBLIC;
    @Size(max = 2048) @HttpUrl
    private String imageUrl;
    @Size(max = 2048) @HttpUrl
    private String videoUrl;
    private boolean contentProvided;
    private boolean imageProvided;
    private boolean videoProvided;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; contentProvided = true; }
    public PostVisibility getVisibility() { return visibility; }
    public void setVisibility(PostVisibility visibility) { this.visibility = visibility; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; imageProvided = true; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; videoProvided = true; }
    public boolean contentProvided() { return contentProvided; }
    public boolean imageProvided() { return imageProvided; }
    public boolean videoProvided() { return videoProvided; }
}
