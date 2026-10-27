package com.aloute.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.dto.response.FeedCursorPage;
import com.aloute.dto.response.PostResponse;
import com.aloute.feed.api.FeedQuery;
import com.aloute.feed.application.FeedPageQuery;
import com.aloute.feed.application.FeedQueryService;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostVisibility;
import com.aloute.model.entity.User;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.posts.api.FeedPostReader;
import com.aloute.posts.infrastructure.PostFeedReader;
import com.aloute.repository.*;
import com.aloute.service.ContentAccessService;
import com.aloute.service.PostResponseMapper;
import com.aloute.service.RepostPresentationService;
import com.aloute.social.api.FollowedAccounts;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FeedCachedVisibilityTest {
    enum Revocation { ONLY_ME, PRIVATE_ACCOUNT, PRIVATE_FOLLOWER_REMOVED, BLOCK, LEFT_COMMUNITY, ORIGINAL_PRIVATE, DELETED, HIDDEN, INACTIVE, CLOSE_FRIEND_REMOVED }

    @ParameterizedTest
    @EnumSource(Revocation.class)
    void revocationCannotLeakCachedContentWhenEvictionNeverArrives(Revocation reason) {
        try (Fixture fixture = new Fixture()) {
            if (reason == Revocation.ORIGINAL_PRIVATE) fixture.post.setRepostOfId("origin");
            if (reason == Revocation.LEFT_COMMUNITY) fixture.post.setCommunityId("7");
            if (reason == Revocation.PRIVATE_FOLLOWER_REMOVED) {
                fixture.author.setPrivateAccount(true); fixture.following.add(2L);
            }
            if (reason == Revocation.CLOSE_FRIEND_REMOVED) {
                fixture.post.setVisibility(PostVisibility.CLOSE_FRIENDS);
                fixture.following.add(2L); fixture.followers.add(2L); fixture.closeFriends.add(2L);
            }
            FeedCursorPage initial = fixture.read();
            assertEquals(1, initial.items().size());
            switch (reason) {
                case ONLY_ME -> fixture.post.setVisibility(PostVisibility.ONLY_ME);
                case PRIVATE_ACCOUNT -> fixture.author.setPrivateAccount(true);
                case PRIVATE_FOLLOWER_REMOVED -> fixture.following.clear();
                case BLOCK -> fixture.blocked.add(2L);
                case LEFT_COMMUNITY -> fixture.denied.add("7");
                case ORIGINAL_PRIVATE -> fixture.original.setVisibility(PostVisibility.ONLY_ME);
                case DELETED -> fixture.deleted = true;
                case HIDDEN -> fixture.post.setModerationStatus(ModerationStatus.HIDDEN);
                case INACTIVE -> fixture.author.setActive(false);
                case CLOSE_FRIEND_REMOVED -> fixture.closeFriends.clear();
            }
            assertEquals(1, fixture.cached().items().size());
            FeedCursorPage refreshed = fixture.read();
            assertTrue(refreshed.items().isEmpty(), "cache hit must recheck current permission: " + reason);
            assertEquals(initial.nextCursor(), refreshed.nextCursor());
            assertEquals(initial.hasMore(), refreshed.hasMore());
            verify(fixture.store, times(2)).find(any(Query.class), eq(Post.class));
        }
    }

    @Test
    void cachedQuotesReloadCurrentBodyAndNestedOriginalWithoutMutatingCache() {
        try (Fixture fixture = new Fixture()) {
            fixture.post.setRepostOfId("origin");
            fixture.read();
            fixture.post.setContent("edited quote");
            fixture.original.setContent("edited original");
            PostResponse fresh = fixture.read().items().getFirst();
            assertEquals("edited quote", fresh.getContent());
            assertEquals("edited original", fresh.getRepostOf().getContent());
            assertEquals("previously public", fixture.cached().items().getFirst().getContent());
            assertEquals("previously public", fixture.cached().items().getFirst().getRepostOf().getContent());
        }
    }

    @Test
    void databaseFailureOnHitNeverFallsBackToStaleContent() {
        try (Fixture fixture = new Fixture()) {
            fixture.read();
            when(fixture.store.find(any(Query.class), eq(Post.class)))
                    .thenThrow(new IllegalStateException("test database unavailable"));
            assertThrows(IllegalStateException.class, fixture::read);
        }
    }

    private static class Fixture implements AutoCloseable {
        final Post post = post("p");
        final Post original = post("origin");
        final User author = new User();
        final Set<Long> blocked = new HashSet<>();
        final Set<String> denied = new HashSet<>();
        final Set<Long> following = new HashSet<>(), followers = new HashSet<>(), closeFriends = new HashSet<>();
        boolean deleted;
        final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        Fixture() {
            author.setId(2L);
            author.setUsername("author");
            author.setActive(true);
            var access = mock(ContentAccessService.class);
            var reposts = mock(RepostPresentationService.class);
            var mapper = new PostResponseMapper(mock(IPostRepository.class), mock(IUserRepository.class),
                    mock(ILikeRepository.class), mock(IRepostRepository.class), access, reposts);
            when(store.find(any(Query.class), eq(Post.class))).thenAnswer(call -> deleted ? List.of() : List.of(post));
            when(access.forPosts(anyCollection(), eq(1L))).thenAnswer(call ->
                    new ContentAccessService.AccessBatch(1L, Map.of(2L, author), blocked,
                            following, followers, Map.of("origin", original), denied, closeFriends));
            context.register(FeedWiringTest.CacheConfiguration.class);
            context.registerBean(CacheManager.class, () -> new CaffeineCacheManager("feedPages"));
            context.registerBean(FeedPostReader.class, () -> new PostFeedReader(
                    store, access, mapper, reposts, mock(FollowedAccounts.class)));
            context.register(FeedPageQuery.class, FeedQueryService.class);
            context.refresh();
        }

        FeedCursorPage read() {
            return context.getBean(FeedQuery.class).getCursorFeedForViewer(1L, "foryou", null, null, 20);
        }

        FeedCursorPage cached() {
            return context.getBean(CacheManager.class).getCache("feedPages").get(
                    com.aloute.service.FeedCacheKey.createForViewer(1L, "foryou", null, null, 20), FeedCursorPage.class);
        }

        public void close() { context.close(); }

        private static Post post(String id) {
            Post p = new Post();
            p.setId(id);
            p.setUserId("2");
            p.setContent("previously public");
            p.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0));
            p.setVisibility(PostVisibility.PUBLIC);
            return p;
        }
    }
}
