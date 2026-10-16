package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;
import java.util.stream.IntStream;

class ContentAccessBatchTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final IFriendRepository friends = mock(IFriendRepository.class);
    private final ContentAccessService service = new ContentAccessService(mongo, users, friends);

    @Test
    void publicBatchUsesTwoQueriesRegardlessOfPostCount() {
        var authors = IntStream.rangeClosed(1, 120).mapToObj(i -> author(i, false)).toList();
        var posts =
                IntStream.rangeClosed(1, 120)
                        .mapToObj(i -> post(i, PostVisibility.PUBLIC))
                        .toList();
        when(users.findAllById(any())).thenReturn(authors);
        var batch = service.forPosts(posts, 999L);
        assertEquals(120, batch.readable(posts).size());
        assertEquals(120, batch.readable(posts).size());
        verify(users, times(1)).findAllById(any());
        verify(mongo, times(1)).find(any(Query.class), eq(UserBlock.class));
        verifyNoMoreInteractions(users, mongo);
        verifyNoInteractions(friends);
    }

    @Test
    void relationshipsAndBlocksAreAppliedWithoutPerPostQueries() {
        when(users.findAllById(any()))
                .thenReturn(
                        List.of(
                                author(1, true),
                                author(2, false),
                                author(3, false),
                                author(4, false)));
        when(friends.findByFollowerIdAndFollowedIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(99L, 1L), new Friend(99L, 2L)));
        when(friends.findByFollowedIdAndFollowerIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(2L, 99L)));
        UserBlock block = new UserBlock();
        block.setOwnerId(3L);
        block.setTargetId(99L);
        when(mongo.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of(block));
        var posts =
                List.of(
                        post(1, PostVisibility.PUBLIC),
                        post(2, PostVisibility.FRIENDS),
                        post(3, PostVisibility.PUBLIC),
                        post(4, PostVisibility.ONLY_ME));
        var batch = service.forPosts(posts, 99L);
        assertEquals(List.of(posts.get(0), posts.get(1)), batch.readable(posts));
        verify(friends, times(1)).findByFollowerIdAndFollowedIdIn(eq(99L), any());
        verify(friends, times(1)).findByFollowedIdAndFollowerIdIn(eq(99L), any());
        verifyNoMoreInteractions(friends);
    }

    @Test
    void ownRepostCannotBypassOriginalVisibility() {
        Post original = post(1, PostVisibility.ONLY_ME);
        original.setId("original");
        Post repost = post(2, PostVisibility.PUBLIC);
        repost.setRepostOfId("original");
        when(users.findAllById(any())).thenReturn(List.of(author(1, false), author(2, false)));
        when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of(original));
        assertFalse(service.forPosts(List.of(repost), 2L).canRead(repost));
        verify(mongo, never()).findById(anyString(), eq(Post.class));
    }

    @Test
    void batchRulesMatchSingleRulesForVisibilityModerationAndOwnership() {
        for (PostVisibility visibility : PostVisibility.values()) {
            for (ModerationStatus moderation : ModerationStatus.values()) {
                for (Long viewer : Arrays.asList(null, 1L, 2L)) {
                    User author = author(1, false);
                    Post post = post(1, visibility);
                    post.setModerationStatus(moderation);
                    when(users.findById(1L)).thenReturn(Optional.of(author));
                    when(users.findAllById(any())).thenReturn(List.of(author));
                    assertEquals(
                            service.canRead(post, viewer),
                            service.forPosts(List.of(post), viewer).canRead(post));
                }
            }
        }
    }

    private User author(long id, boolean privateAccount) {
        User user = new User();
        user.setId(id);
        user.setPrivateAccount(privateAccount);
        return user;
    }

    private Post post(long author, PostVisibility visibility) {
        Post post = new Post();
        post.setId("post-" + author);
        post.setUserId(Long.toString(author));
        post.setVisibility(visibility);
        return post;
    }
}
