package com.aloute.dto.response;

import java.util.ArrayList;
import java.util.List;

public class RepostResponse {

    private String postId;

    private boolean reposted;

    private int repostCount;

    private String repostId;

    private List<PostResponse.RepostActor> reposters = new ArrayList<>();

    private int otherReposterCount;

    public RepostResponse() {}

    public RepostResponse(String postId, boolean reposted, int repostCount, String repostId) {
        this.postId = postId;
        this.reposted = reposted;
        this.repostCount = repostCount;
        this.repostId = repostId;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public boolean isReposted() {
        return reposted;
    }

    public void setReposted(boolean reposted) {
        this.reposted = reposted;
    }

    public int getRepostCount() {
        return repostCount;
    }

    public void setRepostCount(int repostCount) {
        this.repostCount = repostCount;
    }

    public String getRepostId() {
        return repostId;
    }

    public void setRepostId(String repostId) {
        this.repostId = repostId;
    }

    public List<PostResponse.RepostActor> getReposters() {
        return reposters;
    }

    public void setReposters(List<PostResponse.RepostActor> reposters) {
        this.reposters = reposters != null ? reposters : new ArrayList<>();
    }

    public int getOtherReposterCount() {
        return otherReposterCount;
    }

    public void setOtherReposterCount(int otherReposterCount) {
        this.otherReposterCount = Math.max(0, otherReposterCount);
    }
}
