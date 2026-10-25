package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.dto.response.*;
import com.aloute.feed.api.FeedQuery;
import com.aloute.feed.application.*;
import com.aloute.posts.api.FeedPostReader;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.cache.CacheManager;

@EnabledIfEnvironmentVariable(named="ALOUTE_REDIS_VALIDATION_PORT",matches="56379")
class RedisFeedCacheIT {
    @Test void realRedisRoundTripKeepsNanoDatesAndReauthorizesEveryHit() {
        run(56379,false);
    }
    @Test void redisCacheOutageFallsBackToQueryWithoutServingStaleCards() {
        run(56380,true); // Dedicated absent test endpoint, never a configured external server.
    }
    private void run(int port,boolean unavailable) {
        var settings=new RedisStandaloneConfiguration("127.0.0.1",port);settings.setDatabase(11);
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build();
        var factory=new LettuceConnectionFactory(settings,client);factory.afterPropertiesSet();factory.start();
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-test",Map.of("app.cache.redis.enabled","true")));
            context.registerBean(org.springframework.data.redis.connection.RedisConnectionFactory.class,()->factory);
            var posts=mock(FeedPostReader.class);var card=new PostResponse();card.setId("redis-test");
            card.setCreatedAt(LocalDateTime.parse("2026-10-23T00:21:23.183036801"));
            when(posts.readBatch(any(),any(),anyBoolean(),anyBoolean(),any(),any(),anyInt()))
                    .thenReturn(List.of(new FeedPostReader.Candidate(new FeedPostReader.Position(card.getCreatedAt(),card.getId()),card)));
            when(posts.refreshCards(any(),any())).thenReturn(List.of());
            context.register(CacheConfig.class,FeedPageQuery.class,FeedQueryService.class);
            context.registerBean(FeedPostReader.class,()->posts);context.refresh();
            long viewer=Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000000000L);
            var feed=context.getBean(FeedQuery.class);var cache=context.getBean(CacheManager.class).getCache("feedPages");
            String key=com.aloute.service.FeedCacheKey.createForViewer(viewer,"foryou",null,null,20);
            try {
                assertEquals(card.getCreatedAt(),feed.getCursorFeedForViewer(viewer,"foryou",null,null,20).items().getFirst().getCreatedAt());
                var second=feed.getCursorFeedForViewer(viewer,"foryou",null,null,20);
                if(unavailable) {
                    assertEquals(1,second.items().size());
                    verify(posts,times(2)).readBatch(any(),any(),anyBoolean(),anyBoolean(),any(),any(),anyInt());
                } else {
                    assertTrue(second.items().isEmpty());
                    verify(posts,times(1)).readBatch(any(),any(),anyBoolean(),anyBoolean(),any(),any(),anyInt());
                    verify(posts,times(1)).refreshCards(eq(viewer),eq(List.of(card.getId())));
                    assertNotNull(cache.get(key,FeedCursorPage.class));
                    assertEquals(card.getCreatedAt(),cache.get(key,FeedCursorPage.class).items().getFirst().getCreatedAt());
                }
            } finally { if(!unavailable) cache.evict(key); }
        } finally {factory.destroy();}
    }
}
