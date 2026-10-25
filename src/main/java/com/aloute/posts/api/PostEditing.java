package com.aloute.posts.api;

import java.util.List;

public interface PostEditing {
    void edit(Long actorId, String postId, PostEditCommand command);
    List<PostRevisionView> history(Long actorId, String postId);
}
