package com.aloute.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.cache.support.SimpleCacheManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.cache.redis.enabled", havingValue = "true")
    CacheManager redisCacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${app.cache.ttl:2m}") Duration defaultTtl,
            @Value("${app.cache.feed-ttl:30s}") Duration feedTtl) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig().entryTtl(defaultTtl);
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                "trendingTopics", defaults.entryTtl(defaultTtl),
                "feedPages", defaults.entryTtl(feedTtl));
        // SCAN clears cache entries in bounded batches; the default KEYS strategy can block Redis
        // while personalized feed caches grow.
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(
                connectionFactory, BatchStrategies.scan(1_000));
        return RedisCacheManager.builder(writer)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
            name = "app.cache.redis.enabled",
            havingValue = "false",
            matchIfMissing = true)
    CacheManager localCacheManager(
            @Value("${app.cache.ttl:2m}") Duration defaultTtl,
            @Value("${app.cache.feed-ttl:30s}") Duration feedTtl) {
        CaffeineCache trending = new CaffeineCache(
                "trendingTopics", Caffeine.newBuilder().maximumSize(200).expireAfterWrite(defaultTtl).build());
        CaffeineCache feeds = new CaffeineCache(
                "feedPages", Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(feedTtl).build());
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(trending, feeds));
        return manager;
    }

    /**
     * Cache is an optimization, not an application dependency. A temporary Redis cache outage must
     * behave like a cache miss; security-sensitive rate limiting keeps its own fail-closed policy.
     */
    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                warn("get", cache, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                warn("put", cache, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                warn("evict", cache, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                warn("clear", cache, exception);
            }

            private void warn(String operation, Cache cache, RuntimeException exception) {
                log.warn("Cache {} failed for {}; proceeding without cache ({})",
                        operation,
                        cache == null ? "unknown-cache" : cache.getName(),
                        exception.getClass().getSimpleName());
            }
        };
    }
}
