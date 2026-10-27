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
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;

class RepostPresentationServiceTest {

    private final IRepostRepository reposts = mock(IRepostRepository.class);
    private final IPostRepository posts = mock(IPostRepository.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final RepostPresentationService service =
            new RepostPresentationService(reposts, posts, users, documentStore);

    @Test
    void feedShowsOnlyVisibleRepostersAndCountsTheRestOfThem() {
        List<Repost> recent = List.of(repost("r3", "3"), repost("r2", "2"), repost("r1", "1"));
        when(documentStore.find(any(Query.class), eq(Repost.class))).thenReturn(recent);
        when(documentStore.count(any(Query.class), eq(Repost.class))).thenReturn(5L);
        when(users.findAllById(any())).thenReturn(List.of(user(1), user(2), user(3)));
        PostResponse response = response("post-1", 100);

        service.attachRecentReposters(9L, List.of(response));

        assertEquals(3, response.getRecentReposters().size());
        // 5 visible reposters - 3 named in the banner = 2 listed in the popover (not 97).
        assertEquals(2, response.getOtherReposterCount());
        assertTrue(response.getOtherReposters().isEmpty());
        verify(documentStore, never()).updateFirst(any(Query.class), any(Update.class), eq(Post.class));
    }

    @Test
    void fewerThanThreeVisibleRepostersSkipsTheCountQuery() {
        when(documentStore.find(any(Query.class), eq(Repost.class)))
                .thenReturn(List.of(repost("r1", "1")));
        when(users.findAllById(any())).thenReturn(List.of(user(1)));
        PostResponse response = response("post-1", 40);

        service.attachRecentReposters(9L, List.of(response));

        assertEquals(1, response.getRecentReposters().size());
        assertEquals(0, response.getOtherReposterCount());
        verify(documentStore, never()).count(any(Query.class), eq(Repost.class));
    }

    @Test
    void anonymousViewerSeesNoReposters() {
        PostResponse response = response("post-1", 10);

        service.attachRecentReposters(null, List.of(response));

        assertTrue(response.getRecentReposters().isEmpty());
        assertEquals(0, response.getOtherReposterCount());
        verify(documentStore, never()).find(any(Query.class), eq(Repost.class));
    }

    @Test
    void popoverListsOnlyVisibleRepostersAfterTheNamedPreview() {
        List<Repost> named = List.of(repost("r9", "9"), repost("r8", "8"), repost("r7", "7"));
        List<Repost> older = List.of(repost("r3", "3"), repost("r2", "2"), repost("r1", "1"));
        when(documentStore.count(any(Query.class), eq(Repost.class))).thenReturn(7L);
        when(documentStore.find(any(Query.class), eq(Repost.class)))
                .thenReturn(named).thenReturn(older);
        when(users.findAllById(any())).thenReturn(List.of(user(1), user(2), user(3)));

        var page = service.loadRepostActorsPage(9L, "post-1", null, 2);

        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertFalse(page.nextCursor().isBlank());
        assertEquals(4, page.totalCount());
    }

    @Test
    void popoverIsEmptyWhenEveryVisibleReposterIsAlreadyNamed() {
        when(documentStore.count(any(Query.class), eq(Repost.class))).thenReturn(3L);

        var page = service.loadRepostActorsPage(9L, "post-1", null, 20);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertEquals(0, page.totalCount());
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
