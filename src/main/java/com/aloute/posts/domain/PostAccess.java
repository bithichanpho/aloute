package com.aloute.posts.domain;

import com.aloute.model.document.Post;

/** Owner-internal policy port; managed post data must never cross the public posts.api boundary. */
public interface PostAccess {
    Post requireReadable(String postId, Long viewerId);
}
