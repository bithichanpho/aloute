package com.aloute.dto.response;

import java.util.ArrayList;
import java.util.List;

/**
 * Kết quả của thao tác toggle "Đăng lại" (repost).
 * Front-end (app.js) đọc: {@code reposted} để bật/tắt màu nút, {@code repostCount} để cập nhật số đếm.
 */
public class RepostResponse {

    /** Id bài viết GỐC (luôn quy về bài gốc thật sự, kể cả khi người dùng bấm repost trên 1 bài repost). */
    private String postId;

    /** true = vừa đăng lại; false = vừa bỏ đăng lại. */
    private boolean reposted;

    /** Tổng số lượt đăng lại của bài gốc SAU thao tác này. */
    private int repostCount;

    /** Id của bài repost vừa tạo; null khi thao tác là "bỏ đăng lại". */
    private String repostId;

    /** Danh sách đầy đủ người đang repost bài viết, phục vụ cập nhật UI ngay sau toggle. */
    private List<PostResponse.RepostActor> reposters = new ArrayList<>();

    public RepostResponse() {
    }

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
}