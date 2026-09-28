package com.aloute.dto.response;

import java.util.Map;

public class ReactionResponse {

    private String postId;

    private String currentUserReaction;
    private int totalCount;
    private Map<String, Integer> reactionCounts;

    public ReactionResponse() {}

    public ReactionResponse(
            String postId,
            String currentUserReaction,
            int totalCount,
            Map<String, Integer> reactionCounts) {
        this.postId = postId;
        this.currentUserReaction = currentUserReaction;
        this.totalCount = totalCount;
        this.reactionCounts = reactionCounts;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getCurrentUserReaction() {
        return currentUserReaction;
    }

    public void setCurrentUserReaction(String currentUserReaction) {
        this.currentUserReaction = currentUserReaction;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public Map<String, Integer> getReactionCounts() {
        return reactionCounts;
    }

    public void setReactionCounts(Map<String, Integer> reactionCounts) {
        this.reactionCounts = reactionCounts;
    }
}
