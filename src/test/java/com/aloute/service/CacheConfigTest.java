package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import com.aloute.config.CacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class CacheConfigTest {
    @Test
    void cacheReadFailureIsFailOpenBecauseCacheIsOnlyAnOptimization() {
        assertDoesNotThrow(() -> new CacheConfig().errorHandler()
                .handleCacheGetError(new IllegalStateException("Redis unavailable"), mock(Cache.class), "key"));
    }

    @Test
    void cacheWriteAndEvictionFailuresAreAlsoFailOpen() {
        var handler = new CacheConfig().errorHandler();
        Cache cache = mock(Cache.class);
        assertDoesNotThrow(() -> handler.handleCachePutError(
                new IllegalStateException("Redis unavailable"), cache, "key", "value"));
        assertDoesNotThrow(() -> handler.handleCacheEvictError(
                new IllegalStateException("Redis unavailable"), cache, "key"));
        assertDoesNotThrow(() -> handler.handleCacheClearError(
                new IllegalStateException("Redis unavailable"), cache));
    }
}
