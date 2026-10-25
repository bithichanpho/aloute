package com.aloute.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.dto.response.FeedCursorPage;
import com.aloute.dto.response.PostResponse;
import com.aloute.feed.api.FeedQuery;
import com.aloute.feed.application.FeedPageQuery;
import com.aloute.feed.application.FeedQueryService;
import com.aloute.posts.api.FeedPostReader;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FeedCacheBoundaryTest {
    @Test
    void concurrentLocalMissesShareCandidateLoadButEveryWaiterRechecksPermission() throws Exception {
        FeedPostReader posts=mock(FeedPostReader.class);
        var card=new PostResponse();card.setId("p");
        when(posts.readBatch(any(),any(),anyBoolean(),anyBoolean(),any(),any(),anyInt())).thenAnswer(call->{
            Thread.sleep(100);
            return List.of(new FeedPostReader.Candidate(new FeedPostReader.Position(LocalDateTime.of(2026,10,24,10,0),"p"),card));
        });
        when(posts.refreshCards(1L,List.of("p"))).thenReturn(List.of(card));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(FeedWiringTest.CacheConfiguration.class,FeedPageQuery.class,FeedQueryService.class);
            context.registerBean(CacheManager.class,()->new CaffeineCacheManager("feedPages"));
            context.registerBean(FeedPostReader.class,()->posts);context.refresh();
            var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var start=new java.util.concurrent.CountDownLatch(1);
                var calls=new java.util.ArrayList<java.util.concurrent.Future<FeedCursorPage>>();
                for(int i=0;i<8;i++)calls.add(workers.submit(()->{start.await();return context.getBean(FeedQuery.class).getCursorFeedForViewer(1L,"foryou",null,null,20);}));
                start.countDown();for(var call:calls)assertEquals(1,call.get(5,java.util.concurrent.TimeUnit.SECONDS).items().size());
                verify(posts,times(1)).readBatch(any(),any(),anyBoolean(),anyBoolean(),any(),any(),anyInt());
                verify(posts,times(7)).refreshCards(1L,List.of("p"));
            } finally {workers.shutdownNow();}
        }
    }
    @Test
    void cacheMissDoesNotHydrateTwiceAndBothHitPathsRecheckWithoutChangingContinuation() {
        FeedPostReader posts = mock(FeedPostReader.class);
        PostResponse card = new PostResponse(); card.setId("p");
        when(posts.readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyInt()))
                .thenReturn(List.of(new FeedPostReader.Candidate(
                        new FeedPostReader.Position(LocalDateTime.of(2026, 10, 24, 10, 0), "p"), card)));
        when(posts.refreshCards(1L, List.of("p"))).thenReturn(List.of());
        when(posts.refreshFollowingCards(1L, List.of("p"))).thenReturn(List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(FeedWiringTest.CacheConfiguration.class, FeedPageQuery.class, FeedQueryService.class);
            context.registerBean(CacheManager.class, () -> new CaffeineCacheManager("feedPages"));
            context.registerBean(FeedPostReader.class, () -> posts);
            context.refresh();
            FeedQuery feed = context.getBean(FeedQuery.class);
            FeedCursorPage initial = feed.getCursorFeedForViewer(1L, "foryou", null, null, 20);
            verify(posts, never()).refreshCards(any(), any());
            FeedCursorPage hit = feed.getCursorFeedForViewer(1L, "foryou", null, null, 20);
            assertTrue(hit.items().isEmpty());
            assertEquals(initial.nextCursor(), hit.nextCursor());
            assertEquals(initial.hasMore(), hit.hasMore());
            verify(posts, times(1)).readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyInt());

            var cache = context.getBean(CacheManager.class).getCache("feedPages");
            var oldKey = com.aloute.service.FeedCacheKey.create(1L, List.of(2L), "following", null, null, 20);
            cache.put(oldKey, new FeedCursorPage(List.of(card), "existing-cursor", true));
            FeedCursorPage legacy = feed.getCursorFeed(1L, List.of(2L), "following", null, null, 20);
            assertTrue(legacy.items().isEmpty());
            assertEquals("existing-cursor", legacy.nextCursor());
            assertTrue(legacy.hasMore());
            verify(posts, times(1)).refreshCards(1L, List.of("p"));
            verify(posts, times(1)).refreshFollowingCards(1L, List.of("p"));
        }
    }

    @Test
    void publicAndLegacyFacadesCannotCacheAroundTheAuthorizationBoundary() {
        for (Class<?> type : List.of(FeedQueryService.class, com.aloute.service.PostService.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (method.getName().startsWith("getCursorFeed")) {
                    assertNull(method.getAnnotation(org.springframework.cache.annotation.Cacheable.class),
                            "a facade cache would bypass current authorization: " + method);
                }
            }
        }
    }
}
