package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.feed.api.FeedInvalidation;
import com.aloute.feed.application.PostFeedInvalidationListener;
import com.aloute.model.document.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.api.*;
import com.aloute.posts.application.*;
import com.aloute.posts.infrastructure.LegacyPostAccess;
import com.aloute.repository.*;
import com.aloute.service.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PostUseCaseWiringTest {
    @Test
    void productionFacadeUsesOwnedPostServicesAndFeedReceivesMinimalChangeEvent() {
        try (var context = context()) {
            assertEquals(1, context.getBeansOfType(PostEditing.class).size());
            assertEquals(1, context.getBeansOfType(SavedPosts.class).size());
            assertEquals(1, context.getBeansOfType(FeedInvalidation.class).size());
            var cache = context.getBean(CacheManager.class).getCache("feedPages");
            cache.put("cursor:2:page", "public card");
            Post post = new Post();
            post.setId("p"); post.setUserId("1"); post.setContent("same text");
            post.setVisibility(PostVisibility.PUBLIC); post.setModerationStatus(ModerationStatus.APPROVED);
            when(context.getBean(ContentAccessService.class).requirePost("p", 1L)).thenReturn(post);
            when(context.getBean(PostgresDocumentStore.class).updateFirst(any(), any(), eq(Post.class)))
                    .thenReturn(new UpdateResult(1, 1));
            var request = new com.aloute.dto.request.CreatePostRequest();
            request.setContent("same text"); request.setVisibility(PostVisibility.ONLY_ME);

            context.getBean(SocialFeatureService.class).edit(1L, "p", request);

            assertNull(cache.get("cursor:2:page"));
            // The production path reaches Feed through events, never the PostService cache facade.
            verify(context.getBean(PostService.class), never()).invalidateAllFeedCache();
        }
    }

    @Test
    void legacyHistoryResponseRetainsItsExactJsonFieldValues() {
        try (var context = context()) {
            Post post = new Post(); post.setId("p"); post.setUserId("1");
            PostRevision revision = new PostRevision();
            revision.setPostId("p"); revision.setContent("old text");
            revision.setImageUrl("image"); revision.setVideoUrl(null);
            revision.setVisibility("PUBLIC"); revision.setEditorId(1L);
            revision.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
            post.setEditHistory(List.of(revision));
            when(context.getBean(ContentAccessService.class).requirePost("p", 1L)).thenReturn(post);

            var response = context.getBean(SocialFeatureService.class).history(1L, "p");

            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            assertEquals(mapper.valueToTree(List.of(revision)), mapper.valueToTree(response));
            assertNotSame(revision, response.getFirst());
        }
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        for (Class<?> type : List.of(PostgresDocumentStore.class, ContentAccessService.class,
                IUserRepository.class, IFriendRepository.class, ICloseFriendRepository.class,
                PostService.class, AuthRateLimitService.class, Notifications.class,
                com.aloute.users.api.UserDirectory.class, com.aloute.users.api.UserAccountAdministration.class,
                com.aloute.administration.api.AccountModeration.class,
                RefreshTokenService.class, AuthenticationUserCache.class,
                MediaAssetLifecycleService.class, ModerationService.class, PostModerationCoordinator.class)) {
            registerMock(context, type);
        }
        context.registerBean(CacheManager.class, () -> {
            var manager = new SimpleCacheManager();
            manager.setCaches(List.of(new CaffeineCache("feedPages",
                    com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(100).build())));
            manager.afterPropertiesSet();
            return manager;
        });
        context.register(PostEditingService.class, SavedPostsService.class, LegacyPostAccess.class,
                FeedCacheInvalidator.class, PostFeedInvalidationListener.class, SocialFeatureService.class);
        context.refresh();
        return context;
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
