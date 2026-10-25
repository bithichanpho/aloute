package com.aloute.posts.api;

import com.aloute.dto.response.PostResponse;
import java.util.List;

public interface SavedPosts {
    void save(Long viewerId, String postId, boolean saved);
    List<PostResponse> saved(Long viewerId, int page);
}
