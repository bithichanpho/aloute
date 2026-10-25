package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.dto.request.CreatePostRequest;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostVisibility;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.UpdateResult;
import com.aloute.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class PostVisibilityCacheRegressionTest {
    @Test
    void changingPublicPostToOnlyMeEvictsCopiesAlreadyCachedForOtherViewers() {
        var cache = new CaffeineCache("feedPages",
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(100).build());
        cache.put("cursor:1:page", "author's card");
        cache.put("cursor:2:page", "previously public card");
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(cache));
        manager.afterPropertiesSet();
        var invalidator = new FeedCacheInvalidator(manager,
                new DefaultListableBeanFactory().getBeanProvider(RedisConnectionFactory.class));
        var posts = mock(PostService.class);
        doAnswer(call -> { invalidator.invalidateViewer(call.getArgument(0)); return null; })
                .when(posts).invalidateViewerFeedCache(anyLong());
        doAnswer(call -> { invalidator.invalidateAll(); return null; }).when(posts).invalidateAllFeedCache();
        var access = mock(ContentAccessService.class);
        Post post = new Post();
        post.setId("post");
        post.setUserId("1");
        post.setContent("same text");
        post.setVisibility(PostVisibility.PUBLIC);
        post.setModerationStatus(ModerationStatus.APPROVED);
        when(access.requirePost("post", 1L)).thenReturn(post);
        var store = mock(PostgresDocumentStore.class);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        var service = new SocialFeatureService(store, access, mock(IUserRepository.class),
                mock(IFriendRepository.class), mock(ICloseFriendRepository.class), posts,
                mock(ModerationService.class), mock(AuthRateLimitService.class), mock(NotificationService.class));
        var request = new CreatePostRequest();
        request.setContent("same text");
        request.setVisibility(PostVisibility.ONLY_ME);

        service.edit(1L, "post", request);

        assertNull(cache.get("cursor:2:page"), "A privacy-only edit must revoke other viewers' cached copies");
    }
}
