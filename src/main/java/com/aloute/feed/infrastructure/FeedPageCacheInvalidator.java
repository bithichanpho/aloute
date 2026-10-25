package com.aloute.feed.infrastructure;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Invalidates personalized cursor-feed pages without blocking Redis with a KEYS command.
 * Ordinary mutations evict only affected viewers; content-removal and visibility/security changes
 * may scan and evict the whole feed cache so deleted or newly restricted content is not served stale.
 */
public class FeedPageCacheInvalidator implements com.aloute.feed.api.FeedInvalidation {
    private static final Logger log = LoggerFactory.getLogger(FeedPageCacheInvalidator.class);
    private static final String CACHE_NAME = "feedPages";
    private static final int REDIS_DELETE_BATCH_SIZE = 200;

    private final CacheManager cacheManager;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactories;

    public FeedPageCacheInvalidator(
            CacheManager cacheManager,
            ObjectProvider<RedisConnectionFactory> redisConnectionFactories) {
        this.cacheManager = cacheManager;
        this.redisConnectionFactories = redisConnectionFactories;
    }

    public void invalidateViewer(Long viewerId) {
        if (viewerId == null) return;
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) return;

        String viewerPrefix = "cursor:" + viewerId + ":";
        try {
            if (cache instanceof CaffeineCache caffeineCache) {
                caffeineCache.getNativeCache().asMap().keySet().removeIf(
                        key -> key instanceof String text && text.startsWith(viewerPrefix));
                return;
            }
            if (cache instanceof RedisCache) {
                RedisConnectionFactory factory = redisConnectionFactories.getIfAvailable();
                if (factory != null) invalidateRedisViewerKeys(factory, viewerPrefix);
            }
        } catch (RuntimeException ex) {
            // Cache eviction is best-effort; never fail a committed reaction because cache is down.
            log.warn("Could not invalidate personalized feed cache for viewer {}; TTL will expire it",
                    viewerId, ex);
        }
    }

    public void invalidateAll() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) return;
        try {
            if (cache instanceof CaffeineCache caffeineCache) {
                caffeineCache.clear();
                return;
            }
            if (cache instanceof RedisCache) {
                RedisConnectionFactory factory = redisConnectionFactories.getIfAvailable();
                if (factory != null) invalidateRedisKeys(factory, CACHE_NAME + "::*");
            }
        } catch (RuntimeException ex) {
            log.warn("Could not invalidate all feed cache entries; TTL will expire them", ex);
        }
    }

    private void invalidateRedisViewerKeys(RedisConnectionFactory factory, String viewerPrefix) {
        // Spring's default RedisCache prefix is '<cache name>::' and the default key serializer is UTF-8.
        invalidateRedisKeys(factory, CACHE_NAME + "::" + viewerPrefix + "*");
    }

    private void invalidateRedisKeys(RedisConnectionFactory factory, String pattern) {
        RedisConnection connection = factory.getConnection();
        try {
            Cursor<byte[]> cursor = connection.keyCommands().scan(
                    ScanOptions.scanOptions().match(pattern).count(500).build());
            try {
                List<byte[]> batch = new ArrayList<>(REDIS_DELETE_BATCH_SIZE);
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= REDIS_DELETE_BATCH_SIZE) {
                        deleteBatch(connection, batch);
                    }
                }
                if (!batch.isEmpty()) deleteBatch(connection, batch);
            } finally {
                cursor.close();
            }
        } finally {
            connection.close();
        }
    }

    private void deleteBatch(RedisConnection connection, List<byte[]> batch) {
        connection.keyCommands().del(batch.toArray(new byte[0][]));
        batch.clear();
    }
}
