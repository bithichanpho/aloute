package com.aloute.posts.application;

import com.aloute.posts.api.SavedPosts;
import com.aloute.posts.api.PostCards;
import com.aloute.posts.domain.PostAccess;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.SavedPost;
import com.aloute.persistence.postgres.document.*;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedPostsService implements SavedPosts {
    private final PostgresDocumentStore documentStore;
    private final PostAccess access;
    private final PostCards posts;

    public SavedPostsService(PostgresDocumentStore documentStore, PostAccess access, PostCards posts) {
        this.documentStore = documentStore;
        this.access = access;
        this.posts = posts;
    }

    @Transactional
    public void save(Long user, String postId, boolean saved) {
        if (user == null) throw new com.aloute.common.exception.UnauthorizedException("Cần đăng nhập");
        String id = user + ":" + postId;
        if (!saved) {
            documentStore.remove(Query.query(Criteria.where("_id").is(id)), SavedPost.class);
            return;
        }
        access.requireReadable(postId, user);
        SavedPost item = new SavedPost();
        item.setId(id);
        item.setUserId(user);
        item.setPostId(postId);
        documentStore.save(item);
    }

    public List<PostResponse> saved(Long user, int page) {
        if (user == null) throw new com.aloute.common.exception.UnauthorizedException("Cần đăng nhập");
        List<SavedPost> items =
                documentStore.find(
                        Query.query(Criteria.where("userId").is(user))
                                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                                .skip(Math.max(0, page) * 20L)
                                .limit(20),
                        SavedPost.class);
        return posts.getPostsByIds(items.stream().map(SavedPost::getPostId).toList(), user);
    }

}
