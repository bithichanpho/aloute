package com.aloute.config;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.cache.redis.enabled", havingValue = "true")
    CacheManager redisCacheManager(
            RedisConnectionFactory connectionFactory, @Value("${app.cache.ttl:2m}") Duration ttl) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl))
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
            name = "app.cache.redis.enabled",
            havingValue = "false",
            matchIfMissing = true)
    CacheManager localCacheManager(@Value("${app.cache.ttl:2m}") Duration ttl) {
        CaffeineCacheManager manager = new CaffeineCacheManager("trendingTopics");
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(200).expireAfterWrite(ttl));
        return manager;
    }
}
