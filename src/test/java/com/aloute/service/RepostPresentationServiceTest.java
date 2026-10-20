package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class RepostPresentationServiceTest {

    private final IRepostRepository reposts = mock(IRepostRepository.class);
    private final IPostRepository posts = mock(IPostRepository.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final RepostPresentationService service =
            new RepostPresentationService(reposts, posts, users, mongo);

    @Test
    void feedUsesDenormalizedPreviewWithoutReadingRepostHistory() {
        Post post = post("post-1", 100, List.of("1", "2", "3"));
        when(posts.findAllById(any())).thenReturn(List.of(post));
        when(users.findAllById(any())).thenReturn(List.of(user(1), user(2), user(3)));
        PostResponse response = response("post-1", 100);

        service.attachRecentReposters(List.of(response));

        assertEquals(3, response.getRecentReposters().size());
        assertEquals(97, response.getOtherReposterCount());
        assertTrue(response.getOtherReposters().isEmpty());
        verify(reposts, never()).findByPostIdOrderByCreatedAtDesc(
                eq("post-1"), any(Pageable.class));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
    }

    @Test
    void legacyPostIsBackfilledWithOnlyThreeRecentReposters() {
        Post post = post("post-1", 4, List.of());
        List<Repost> recent = List.of(repost("r3", "3"), repost("r2", "2"), repost("r1", "1"));
        when(posts.findAllById(any())).thenReturn(List.of(post));
        when(reposts.findByPostIdOrderByCreatedAtDesc(eq("post-1"), any(Pageable.class)))
                .thenReturn(recent);
        when(users.findAllById(any())).thenReturn(List.of(user(1), user(2), user(3)));
        PostResponse response = response("post-1", 4);

        service.attachRecentReposters(List.of(response));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(reposts).findByPostIdOrderByCreatedAtDesc(eq("post-1"), page.capture());
        assertEquals(3, page.getValue().getPageSize());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
        assertEquals(3, response.getRecentReposters().size());
        assertEquals(1, response.getOtherReposterCount());
    }

    @Test
    void fullAudienceUsesBoundedCursorQuery() {
        List<Repost> loaded = List.of(repost("r3", "3"), repost("r2", "2"), repost("r1", "1"));
        when(mongo.find(any(Query.class), eq(Repost.class))).thenReturn(loaded);
        when(users.findAllById(any())).thenReturn(List.of(user(1), user(2), user(3)));

        var page = service.loadRepostActorsPage("post-1", null, 2, 25);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Repost.class));
        assertEquals(3, query.getValue().getLimit());
        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertFalse(page.nextCursor().isBlank());
        assertEquals(25, page.totalCount());
    }

    private Post post(String id, int count, List<String> recentIds) {
        Post post = new Post();
        post.setId(id);
        post.setRepostCount(count);
        post.setRecentReposterIds(recentIds);
        return post;
    }

    private PostResponse response(String postId, int count) {
        PostResponse response = new PostResponse();
        response.setInteractionPostId(postId);
        response.setRepostCount(count);
        return response;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setActive(true);
        return user;
    }

    private Repost repost(String id, String userId) {
        Repost repost = new Repost();
        repost.setId(id);
        repost.setPostId("post-1");
        repost.setUserId(userId);
        repost.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, Integer.parseInt(userId)));
        return repost;
    }
}
