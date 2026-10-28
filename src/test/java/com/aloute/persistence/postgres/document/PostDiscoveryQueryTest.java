package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.Post;
import com.aloute.posts.infrastructure.PostFeedReader;
import com.aloute.repository.*;
import com.aloute.service.*;
import com.aloute.social.api.FollowedAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PostDiscoveryQueryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PostgresDocumentStore store = new PostgresDocumentStore(jdbc,
            new ObjectMapper().findAndRegisterModules());

    @ParameterizedTest
    @ValueSource(strings = {"fresh-global", "fresh-following", "cached-global", "cached-following"})
    void discoveryExcludesViewerInSqlBeforeLimitingIncludingPreviouslyCachedPages(String path) {
        var followed = mock(FollowedAccounts.class);
        when(followed.hasFollowing(7L)).thenReturn(true);
        var access = mock(ContentAccessService.class);
        when(access.forPosts(anyCollection(), eq(7L)))
                .thenReturn(mock(ContentAccessService.AccessBatch.class));
        var reader = new PostFeedReader(store, access,
                mock(PostResponseMapper.class), mock(RepostPresentationService.class), followed);
        boolean following = path.endsWith("following");
        if (path.startsWith("fresh")) {
            assertTrue(reader.readBatch(7L, List.of("7", "8"), true, following, "", null, 50).isEmpty());
        } else if (following) {
            assertTrue(reader.refreshFollowingCards(7L, List.of("cached-own", "cached-other")).isEmpty());
        } else {
            assertTrue(reader.refreshCards(7L, List.of("cached-own", "cached-other")).isEmpty());
        }

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertTrue(sql.getValue().contains("payload ->> 'userId' <> ?"), sql.getValue());
        assertTrue(Arrays.asList(parameters.getValue()).contains("7"));
        assertTrue(sql.getValue().contains("LIMIT ?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aloute", "#aloute"})
    void explicitKeywordSearchStillReturnsMatchingOwnPosts(String keyword) {
        Post own = new Post();
        own.setId("own-match");
        own.setUserId("7");
        own.setContent("Bài của tôi #aloute");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(own));
        var access = mock(ContentAccessService.class);
        var mapper = new PostResponseMapper(mock(IPostRepository.class), mock(IUserRepository.class),
                mock(ILikeRepository.class), mock(IRepostRepository.class), access,
                mock(RepostPresentationService.class));
        var permissions = mock(ContentAccessService.AccessBatch.class);
        when(access.forPosts(List.of(own), 7L)).thenReturn(permissions);
        when(permissions.readable(List.of(own))).thenReturn(List.of(own));
        var service = new PostService(mock(IPostRepository.class), mock(IUserRepository.class),
                mock(ILikeRepository.class), mock(ModerationService.class), mock(NotificationService.class),
                store, mock(ICommentRepository.class), mock(INotificationRepository.class),
                mock(IRepostRepository.class), access, mock(RepostPresentationService.class), mapper,
                mock(PostModerationCoordinator.class), mock(MediaAssetLifecycleService.class));

        var results = service.searchPosts(keyword, 7L, 0, 20);
        assertEquals(List.of("own-match"), results.stream().map(p -> p.getId()).toList());
        assertEquals(7L, results.getFirst().getUserId());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertFalse(sql.getValue().contains("payload ->> 'userId' <> ?"));
    }
}
