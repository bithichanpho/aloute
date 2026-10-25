package com.aloute.service;

import com.aloute.feed.infrastructure.FeedPageCacheInvalidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** Compatibility bean; cache implementation belongs to Feed. */
@Component
public class FeedCacheInvalidator extends FeedPageCacheInvalidator {
    public FeedCacheInvalidator(CacheManager cacheManager, ObjectProvider<RedisConnectionFactory> factories) {
        super(cacheManager, factories);
    }
}
