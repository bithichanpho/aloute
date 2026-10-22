package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Post;
import com.aloute.model.entity.User;
import com.aloute.repository.*;
import org.junit.jupiter.api.Test;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

class FeedQueryBudgetTest {
    @Test
    void cursorFeedUsesPostgresKeysetQueryAndDoesNotFetchLegacyJpaFeedWindow() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

        PostService service = newService(relationalPostRepository, documentStore);
        var page = service.getCursorFeed(2L, List.of(1L), "foryou", null, null, 20);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        verify(documentStore, times(1)).find(any(Query.class), eq(Post.class));
        verifyNoInteractions(relationalPostRepository);
    }

    @Test
    void legacyPageApiRejectsUnboundedDeepPageAndDirectsClientsToCursor() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        PostService service = newService(relationalPostRepository, documentStore);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.getFeed(2L, List.of(), 101, 20));

        assertTrue(exception.getMessage().contains("cursor"));
        verifyNoInteractions(documentStore, relationalPostRepository);
    }

    @Test
    void legacyPageZeroDelegatesToTheSameCursorImplementation() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of());
        PostService service = newService(relationalPostRepository, documentStore);

        assertTrue(service.getFeed(2L, List.of(), 0, 20).isEmpty());
        verify(documentStore, times(1)).find(any(Query.class), eq(Post.class));
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
                likes, posts, mock(NotificationService.class), mock(PostgresDocumentStore.class));

        service.setReaction("post", 2L, null);

        verify(posts).requireReadablePost("post", 2L);
        verify(posts, never()).getPost(anyString(), anyLong());
    }

    @Test
    void followingFeedStreamsFollowIdsInBoundedJpaBatches() {
        IPostRepository relationalPostRepository = mock(IPostRepository.class);
        IFriendRepository friendRepository = mock(IFriendRepository.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(friendRepository.findFollowedIdsAfter(eq(2L), eq(0L), any()))
                .thenReturn(List.of(10L, 11L));
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of());
        PostService service = newService(relationalPostRepository, documentStore, friendRepository);

        var page = service.getCursorFeedForViewer(2L, "following", null, null, 20);

        assertTrue(page.items().isEmpty());
        verify(friendRepository).findFollowedIdsAfter(eq(2L), eq(0L), argThat(pageable ->
                pageable.getPageSize() == 500));
        verify(friendRepository, never()).findByFollowerId(anyLong());
        verify(documentStore).find(any(Query.class), eq(Post.class));
    }

    @Test
    void profileCursorMapsInteractionsForTheWholeBatchOnce() {
        IPostRepository posts = mock(IPostRepository.class);
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        ContentAccessService access = mock(ContentAccessService.class);
        PostResponseMapper mapper = mock(PostResponseMapper.class);

        Post first = profilePost("p2", 2L, LocalDateTime.now());
        Post second = profilePost("p1", 2L, first.getCreatedAt().minusMinutes(1));
        List<Post> loaded = List.of(first, second);
        User owner = new User();
        owner.setId(2L);
        owner.setActive(true);
        ContentAccessService.AccessBatch permissions =
                new ContentAccessService.AccessBatch(
                        2L, Map.of(2L, owner), Set.of(), Set.of(), Set.of(), Map.of(), Set.of());
        PostResponse firstResponse = new PostResponse();
        firstResponse.setId("p2");
        PostResponse secondResponse = new PostResponse();
        secondResponse.setId("p1");

        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(loaded);
        when(access.forPosts(loaded, 2L)).thenReturn(permissions);
        when(mapper.toReadableResponses(loaded, 2L, permissions))
                .thenReturn(List.of(firstResponse, secondResponse));

        PostService service =
                new PostService(
                        posts,
                        mock(IUserRepository.class),
                        mock(ILikeRepository.class),
                        mock(ModerationService.class),
                        mock(NotificationService.class),
                        documentStore,
                        mock(ICommentRepository.class),
                        mock(INotificationRepository.class),
                        mock(IRepostRepository.class),
                        access,
                        mock(RepostPresentationService.class),
                        mapper,
                        mock(PostModerationCoordinator.class),
                        mock(MediaAssetLifecycleService.class),
                        mock(FeedCacheInvalidator.class),
                        mock(PostgresTransactionRetryExecutor.class),
                        mock(IFriendRepository.class));

        var page = service.getPostsByUserCursor(2L, 2L, null, 20);

        assertEquals(List.of(firstResponse, secondResponse), page.items());
        verify(access, times(1)).forPosts(loaded, 2L);
        verify(mapper, times(1)).toReadableResponses(loaded, 2L, permissions);
    }

    private static Post profilePost(String id, Long userId, LocalDateTime createdAt) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(String.valueOf(userId));
        post.setCreatedAt(createdAt);
        return post;
    }

    private PostService newService(IPostRepository relationalPostRepository, PostgresDocumentStore documentStore) {
        return new PostService(
                relationalPostRepository,
                mock(IUserRepository.class),
                mock(ILikeRepository.class),
                mock(ModerationService.class),
                mock(NotificationService.class),
                documentStore,
                mock(ICommentRepository.class),
                mock(INotificationRepository.class),
                mock(IRepostRepository.class),
                mock(ContentAccessService.class),
                mock(RepostPresentationService.class),
                mock(PostResponseMapper.class),
                mock(PostModerationCoordinator.class),
                mock(MediaAssetLifecycleService.class));
    }

    private PostService newService(
            IPostRepository relationalPostRepository,
            PostgresDocumentStore documentStore,
            IFriendRepository friendRepository) {
        return new PostService(
                relationalPostRepository,
                mock(IUserRepository.class),
                mock(ILikeRepository.class),
                mock(ModerationService.class),
                mock(NotificationService.class),
                documentStore,
                mock(ICommentRepository.class),
                mock(INotificationRepository.class),
                mock(IRepostRepository.class),
                mock(ContentAccessService.class),
                mock(RepostPresentationService.class),
                mock(PostResponseMapper.class),
                mock(PostModerationCoordinator.class),
                mock(MediaAssetLifecycleService.class),
                mock(FeedCacheInvalidator.class),
                mock(PostgresTransactionRetryExecutor.class),
                friendRepository);
    }
}
