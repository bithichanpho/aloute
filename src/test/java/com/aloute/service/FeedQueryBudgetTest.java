package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.*;
import com.aloute.model.entity.User;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;
import java.util.stream.IntStream;

class FeedQueryBudgetTest {
    @Test
    void rankingBalancesFreshnessEngagementAndRelationship() {
        PostService service =
                new PostService(
                        mock(IPostRepository.class),
                        mock(IUserRepository.class),
                        mock(ILikeRepository.class),
                        mock(ModerationService.class),
                        mock(NotificationService.class),
                        mock(MongoTemplate.class),
                        mock(ICommentRepository.class),
                        mock(INotificationRepository.class),
                        mock(IRepostRepository.class),
                        mock(ImageStorageService.class),
                        mock(ContentAccessService.class));
        Post fresh = new Post();
        fresh.setCreatedAt(java.time.LocalDateTime.now());
        Post engaged = new Post();
        engaged.setCreatedAt(java.time.LocalDateTime.now().minusHours(5));
        engaged.setLikeCount(80);
        engaged.setCommentCount(20);

        assertTrue(service.feedScore(engaged, false, 0) > service.feedScore(fresh, false, 0));
        assertTrue(service.feedScore(fresh, true, 1) > service.feedScore(fresh, false, 0));
    }

    @Test
    void sparseFeedExpandsOnlyWhenFirstWindowHasTooFewReadablePosts() {
        IPostRepository repository = mock(IPostRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        User author = new User();
        author.setId(1L);
        when(users.findAllById(any())).thenReturn(List.of(author));
        when(repository.findAllForFeed(any()))
                .thenAnswer(
                        call -> {
                            Pageable page = call.getArgument(0);
                            return IntStream.range(0, page.getPageSize())
                                    .mapToObj(
                                            i -> {
                                                Post post = new Post();
                                                post.setId("post" + i);
                                                post.setUserId("1");
                                                post.setVisibility(
                                                        i < 40
                                                                ? PostVisibility.ONLY_ME
                                                                : PostVisibility.PUBLIC);
                                                return post;
                                            })
                                    .toList();
                        });
        PostService service =
                new PostService(
                        repository,
                        users,
                        mock(ILikeRepository.class),
                        mock(ModerationService.class),
                        mock(NotificationService.class),
                        mongo,
                        mock(ICommentRepository.class),
                        mock(INotificationRepository.class),
                        mock(IRepostRepository.class),
                        mock(ImageStorageService.class),
                        new ContentAccessService(mongo, users, friends));
        var feed = service.getFeed(2L, List.of(), 0, 20);
        assertEquals(20, feed.size());
        assertTrue(
                feed.stream()
                        .allMatch(post -> Integer.parseInt(post.getId().substring(4)) >= 40));
        verify(repository).findAllForFeed(argThat(page -> page.getPageSize() == 40));
        verify(repository).findAllForFeed(argThat(page -> page.getPageSize() == 80));
        verifyNoMoreInteractions(repository);
        verify(users, never()).findById(anyLong());
        verify(mongo, never()).exists(any(Query.class), eq(UserBlock.class));
    }

    @Test
    void twentyPostFeedLoadsAuthorsAndPermissionsOnlyOnce() {
        IPostRepository posts = mock(IPostRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        IFriendRepository friends = mock(IFriendRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        ContentAccessService access = new ContentAccessService(mongo, users, friends);
        ILikeRepository likes = mock(ILikeRepository.class);
        IRepostRepository reposts = mock(IRepostRepository.class);
        User author = new User();
        author.setId(1L);
        author.setUsername("author");
        when(users.findAllById(any())).thenReturn(List.of(author));
        when(posts.findAllForFeed(any()))
                .thenAnswer(
                        call -> {
                            Pageable page = call.getArgument(0);
                            return new ArrayList<>(
                                    IntStream.range(0, page.getPageSize())
                                            .mapToObj(
                                                    i -> {
                                                        Post post = new Post();
                                                        post.setId("p" + i);
                                                        post.setUserId("1");
                                                        post.setContent("Post " + i);
                                                        return post;
                                                    })
                                            .toList());
                        });
        PostService service =
                new PostService(
                        posts,
                        users,
                        likes,
                        mock(ModerationService.class),
                        mock(NotificationService.class),
                        mongo,
                        mock(ICommentRepository.class),
                        mock(INotificationRepository.class),
                        reposts,
                        mock(ImageStorageService.class),
                        access);
        assertEquals(20, service.getFeed(2L, List.of(), 0, 20).size());
        verify(posts).findAllForFeed(argThat(page -> page.getPageSize() == 40));
        verify(users, times(1)).findAllById(any());
        verify(mongo, times(1)).find(any(Query.class), eq(UserBlock.class));
        verify(likes, times(1)).findByPostIdInAndUserId(any(), eq("2"));
        verify(reposts, times(1)).findByUserIdAndPostIdIn(eq("2"), any());
        verify(reposts, times(1)).findByPostIdIn(any());
        verifyNoMoreInteractions(users, mongo, posts, likes, reposts);
        verifyNoInteractions(friends);
    }

    @Test
    void reactionDoesNotBuildPostResponseForAuthorization() {
        PostService posts = mock(PostService.class);
        ILikeRepository likes = mock(ILikeRepository.class);
        Post post = new Post();
        post.setId("post");
        post.setUserId("1");
        when(posts.requireReadablePost("post", 2L)).thenReturn(post);
        when(posts.getPostOrThrow("post")).thenReturn(post);
        LikeService service =
                new LikeService(
                        likes, posts, mock(NotificationService.class), mock(MongoTemplate.class));
        service.setReaction("post", 2L, null);
        verify(posts).requireReadablePost("post", 2L);
        verify(posts, never()).getPost(anyString(), anyLong());
    }
}
