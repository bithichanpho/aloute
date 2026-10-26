package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;

class FeedCacheInvalidatorTest {
    @Test
    void invalidatesOnlyTheViewerCursorEntries() throws Exception {
        CaffeineCache cache = new CaffeineCache(
                "feedPages", Caffeine.newBuilder().maximumSize(100).build());
        cache.put("cursor:42:foryou::start:20:none", "viewer-42");
        cache.put("cursor:42:following::start:20:[1,2]", "viewer-42-following");
        cache.put("cursor:99:foryou::start:20:none", "viewer-99");

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(cache));
        manager.afterPropertiesSet();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ObjectProvider<RedisConnectionFactory> redisFactory = beanFactory.getBeanProvider(RedisConnectionFactory.class);
        FeedCacheInvalidator invalidator = new FeedCacheInvalidator(manager, redisFactory);

        invalidator.invalidateViewer(42L);

        assertNull(cache.get("cursor:42:foryou::start:20:none"));
        assertNull(cache.get("cursor:42:following::start:20:[1,2]"));
        assertNotNull(cache.get("cursor:99:foryou::start:20:none"));
    }
}
