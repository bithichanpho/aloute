package com.aloute.dto.request;

/**
 * Body khi đặt reaction cho bài viết. "type" là một trong LIKE, LOVE, HAHA, WOW, SAD, ANGRY
 * (xem {@link com.aloute.common.util.ReactionType}). Để trống/null = bỏ reaction hiện tại.
 */
public class ReactionRequest {

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}