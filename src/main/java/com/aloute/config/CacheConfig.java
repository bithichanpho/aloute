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
            @Value("${app.cache.feed-ttl:30s}") Duration feedTtl,
            @Value("${app.cache.host-dashboard-ttl:30s}") Duration hostDashboardTtl) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig().entryTtl(defaultTtl);
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                "trendingTopics", defaults.entryTtl(defaultTtl),
                "feedPages", defaults.entryTtl(feedTtl),
                "relationshipIds", defaults.entryTtl(Duration.ofSeconds(30)),
                "userStatus", defaults.entryTtl(Duration.ofSeconds(15)),
                "hostDashboard", defaults.entryTtl(hostDashboardTtl));
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
            @Value("${app.cache.feed-ttl:30s}") Duration feedTtl,
            @Value("${app.cache.host-dashboard-ttl:30s}") Duration hostDashboardTtl) {
        CaffeineCache trending = new CaffeineCache(
                "trendingTopics", Caffeine.newBuilder().maximumSize(200).expireAfterWrite(defaultTtl).build());
        CaffeineCache feeds = new CaffeineCache(
                "feedPages", Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(feedTtl).build());
        CaffeineCache relationships = new CaffeineCache(
                "relationshipIds", Caffeine.newBuilder().maximumSize(5_000)
                        .expireAfterWrite(Duration.ofSeconds(30)).build());
        CaffeineCache userStatus = new CaffeineCache(
                "userStatus", Caffeine.newBuilder().maximumSize(10)
                        .expireAfterWrite(Duration.ofSeconds(15)).build());
        CaffeineCache hostDashboard = new CaffeineCache(
                "hostDashboard", Caffeine.newBuilder().maximumSize(400)
                        .expireAfterWrite(hostDashboardTtl).build());
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(trending, feeds, relationships, userStatus, hostDashboard));
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
