package com.aloute.dto.response;

public class LikeResponse {

    private String postId;
    private boolean liked;
    private int likeCount;

    public LikeResponse() {
    }

    public LikeResponse(String postId, boolean liked, int likeCount) {
        this.postId = postId;
        this.liked = liked;
        this.likeCount = likeCount;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }
}
