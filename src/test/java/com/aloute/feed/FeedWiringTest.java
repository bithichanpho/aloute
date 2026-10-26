package com.aloute.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.feed.api.FeedQuery;
import com.aloute.feed.application.FeedQueryService;
import com.aloute.posts.api.FeedPostReader;
import com.aloute.posts.infrastructure.PostFeedReader;
import com.aloute.social.infrastructure.JpaFollowedAccounts;
import com.aloute.repository.IFriendRepository;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.service.ContentAccessService;
import com.aloute.service.PostResponseMapper;
import com.aloute.service.RepostPresentationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FeedWiringTest {
    @Test
    void productionControllerSelectsFeedModuleWhileLegacyPostFacadeRemainsConstructible() {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (Class<?> type : java.util.List.of(
                    com.aloute.repository.IPostRepository.class,
                    com.aloute.repository.IUserRepository.class,
                    com.aloute.repository.ILikeRepository.class,
                    com.aloute.repository.ICommentRepository.class,
                    com.aloute.repository.IRepostRepository.class,
                    com.aloute.service.ModerationService.class,
                    com.aloute.notifications.api.Notifications.class,
                    com.aloute.notifications.api.NotificationCleanup.class,
                    PostgresDocumentStore.class, ContentAccessService.class,
                    PostResponseMapper.class, RepostPresentationService.class,
                    com.aloute.service.PostModerationCoordinator.class,
                    com.aloute.service.MediaAssetLifecycleService.class,
                    com.aloute.service.FeedCacheInvalidator.class,
                    com.aloute.service.PostgresTransactionRetryExecutor.class,
                    com.aloute.service.LikeService.class, com.aloute.service.FriendService.class,
                    com.aloute.service.AuthRateLimitService.class, com.aloute.service.ChatService.class,
                    com.aloute.security.ClientIpResolver.class, FeedPostReader.class)) {
                registerMock(context, type);
            }
            context.register(com.aloute.feed.application.FeedPageQuery.class, FeedQueryService.class, com.aloute.service.PostService.class,
                    com.aloute.controller.PostController.class);
            context.refresh();
            assertEquals(2, context.getBeansOfType(FeedQuery.class).size());
            assertInstanceOf(FeedQueryService.class, context.getBean(FeedQuery.class));
            assertNotNull(context.getBean(com.aloute.service.PostService.class));
            assertSame(context.getBean(FeedQuery.class), org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(com.aloute.controller.PostController.class), "feedQuery"));
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }

    @Test
    void cacheKeysKeepViewerIsolationAndExistingInvalidationFormat() {
        FeedPostReader posts = mock(FeedPostReader.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(CacheConfiguration.class);
            context.registerBean(org.springframework.cache.CacheManager.class,
                    () -> new org.springframework.cache.concurrent.ConcurrentMapCacheManager("feedPages"));
            context.registerBean(FeedPostReader.class, () -> posts);
            context.register(com.aloute.feed.application.FeedPageQuery.class, FeedQueryService.class);
            context.refresh();
            var feed = context.getBean(FeedQuery.class);
            feed.getCursorFeedForViewer(1L, "foryou", null, null, 20);
            feed.getCursorFeedForViewer(1L, "foryou", null, null, 20);
            feed.getCursorFeedForViewer(2L, "foryou", null, null, 20);
            verify(posts, times(2)).readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyInt());
            var cache = context.getBean(org.springframework.cache.CacheManager.class).getCache("feedPages");
            assertNotNull(cache.get(com.aloute.service.FeedCacheKey.createForViewer(1L, "foryou", null, null, 20)));
            assertNotNull(cache.get(com.aloute.service.FeedCacheKey.createForViewer(2L, "foryou", null, null, 20)));
        }
    }

    @org.springframework.cache.annotation.EnableCaching(proxyTargetClass = true)
    static class CacheConfiguration {}

    @Test
    void feedPostsAndSocialPortsResolveWithoutDependingBackOnPostService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PostgresDocumentStore.class, () -> mock(PostgresDocumentStore.class));
            context.registerBean(ContentAccessService.class, () -> mock(ContentAccessService.class));
            context.registerBean(PostResponseMapper.class, () -> mock(PostResponseMapper.class));
            context.registerBean(RepostPresentationService.class, () -> mock(RepostPresentationService.class));
            context.registerBean(IFriendRepository.class, () -> mock(IFriendRepository.class));
            context.register(com.aloute.feed.application.FeedPageQuery.class, FeedQueryService.class, PostFeedReader.class, JpaFollowedAccounts.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(FeedQuery.class).size());
            assertEquals(1, context.getBeansOfType(FeedPostReader.class).size());
            assertFalse(context.getBean(FeedQuery.class)
                    .getCursorFeedForViewer(1L, "following", null, null, 20).hasMore());
        }
    }
}
