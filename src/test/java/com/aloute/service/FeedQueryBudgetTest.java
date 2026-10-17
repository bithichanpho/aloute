package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.document.Post;
import com.aloute.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

class FeedQueryBudgetTest {
    @Test
    void cursorFeedUsesMongoKeysetQueryAndDoesNotFetchLegacyJpaFeedWindow() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

        PostService service = newService(relationalPostRepository, mongo);
        var page = service.getCursorFeed(2L, List.of(1L), "foryou", null, null, 20);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        verify(mongo, times(1)).find(any(Query.class), eq(Post.class));
        verifyNoInteractions(relationalPostRepository);
    }

    @Test
    void legacyPageApiRejectsUnboundedDeepPageAndDirectsClientsToCursor() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        PostService service = newService(relationalPostRepository, mongo);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.getFeed(2L, List.of(), 101, 20));

        assertTrue(exception.getMessage().contains("cursor"));
        verifyNoInteractions(mongo, relationalPostRepository);
    }

    @Test
    void legacyPageZeroDelegatesToTheSameCursorImplementation() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());
        PostService service = newService(relationalPostRepository, mongo);

        assertTrue(service.getFeed(2L, List.of(), 0, 20).isEmpty());
        verify(mongo, times(1)).find(any(Query.class), eq(Post.class));
        verifyNoInteractions(relationalPostRepository);
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
        LikeService service = new LikeService(
                likes, posts, mock(NotificationService.class), mock(MongoTemplate.class));

        service.setReaction("post", 2L, null);

        verify(posts).requireReadablePost("post", 2L);
        verify(posts, never()).getPost(anyString(), anyLong());
    }

    private PostService newService(IPostRepository relationalPostRepository, MongoTemplate mongo) {
        return new PostService(
                relationalPostRepository,
                mock(IUserRepository.class),
                mock(ILikeRepository.class),
                mock(ModerationService.class),
                mock(NotificationService.class),
                mongo,
                mock(ICommentRepository.class),
                mock(INotificationRepository.class),
                mock(IRepostRepository.class),
                mock(ContentAccessService.class),
                mock(RepostPresentationService.class),
                mock(PostResponseMapper.class),
                mock(PostModerationCoordinator.class),
                mock(MediaAssetLifecycleService.class));
    }
}
