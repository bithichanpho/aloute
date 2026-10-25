package com.aloute.posts.api;

import com.aloute.dto.response.PostResponse;
import java.util.List;

/** Posts owns batch visibility and card assembly for saved-post queries. */
public interface PostCards {
    List<PostResponse> getPostsByIds(List<String> ids, Long viewerId);
}
