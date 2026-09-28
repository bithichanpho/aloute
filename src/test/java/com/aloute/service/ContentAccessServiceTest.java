package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.*;
import com.aloute.model.entity.User;
import com.aloute.repository.*;

import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Optional;

class ContentAccessServiceTest {
    MongoTemplate mongo = mock(MongoTemplate.class);
    IUserRepository users = mock(IUserRepository.class);
    IFriendRepository friends = mock(IFriendRepository.class);
    ContentAccessService access = new ContentAccessService(mongo, users, friends);
    User author;
    Post post;

    @BeforeEach
    void setup() {
        author = new User();
        author.setId(1L);
        author.setActive(true);
        when(users.findById(1L)).thenReturn(Optional.of(author));
        post = new Post();
        post.setUserId("1");
        post.setId("post");
    }

    @Test
    void publicPostsAreReadableWithoutLogin() {
        assertTrue(access.canRead(post, null));
    }

    @Test
    void blockedViewersCannotReadPublicPosts() {
        when(mongo.exists(any(Query.class), eq(UserBlock.class))).thenReturn(true);
        assertFalse(access.canRead(post, 2L));
    }

    @Test
    void friendsOnlyRequiresBothDirections() {
        post.setVisibility(PostVisibility.FRIENDS);
        when(friends.existsByFollowerIdAndFollowedId(2L, 1L)).thenReturn(true);
        assertFalse(access.canRead(post, 2L));
        when(friends.existsByFollowerIdAndFollowedId(1L, 2L)).thenReturn(true);
        assertTrue(access.canRead(post, 2L));
    }

    @Test
    void onlyMeNeverLeaksToFollowers() {
        post.setVisibility(PostVisibility.ONLY_ME);
        assertFalse(access.canRead(post, 2L));
        assertFalse(access.canRead(post, null));
        assertTrue(access.canRead(post, 1L));
    }

    @Test
    void privateAccountNeedsApprovedFollow() {
        author.setPrivateAccount(true);
        assertFalse(access.canRead(post, 2L));
        when(friends.existsByFollowerIdAndFollowedId(2L, 1L)).thenReturn(true);
        assertTrue(access.canRead(post, 2L));
    }

    @Test
    void inactiveAuthorAndHiddenContentAreExcluded() {
        post.setModerationStatus(ModerationStatus.HIDDEN);
        assertFalse(access.canRead(post, 2L));
        author.setActive(false);
        assertFalse(access.canRead(post, 1L));
    }

    @Test
    void repostCannotExposePrivateOriginal() {
        User sharer = new User();
        sharer.setId(3L);
        when(users.findById(3L)).thenReturn(Optional.of(sharer));
        Post repost = new Post();
        repost.setUserId("3");
        repost.setRepostOfId("post");
        post.setVisibility(PostVisibility.ONLY_ME);
        when(mongo.findById("post", Post.class)).thenReturn(post);
        assertFalse(access.canRead(repost, 2L));
    }
}
