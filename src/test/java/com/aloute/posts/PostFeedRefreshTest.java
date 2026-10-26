package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Post;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.posts.infrastructure.PostFeedReader;
import com.aloute.service.ContentAccessService;
import com.aloute.service.PostResponseMapper;
import com.aloute.service.RepostPresentationService;
import com.aloute.social.api.FollowedAccounts;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PostFeedRefreshTest {
    private final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final PostResponseMapper mapper = mock(PostResponseMapper.class);
    private final RepostPresentationService reposts = mock(RepostPresentationService.class);
    private final PostFeedReader reader = new PostFeedReader(store, access, mapper, reposts, mock(FollowedAccounts.class));

    @Test
    void usesOneBoundedBatchPreservesCachedOrderAndDropsMissingOrUnreadableCards() {
        Post a = new Post(); a.setId("a");
        Post b = new Post(); b.setId("b");
        List<Post> loaded = List.of(b, a);
        var permission = mock(ContentAccessService.AccessBatch.class);
        PostResponse cardA = new PostResponse(); cardA.setId("a");
        when(store.find(any(Query.class), eq(Post.class))).thenReturn(loaded);
        when(access.forPosts(loaded, 1L)).thenReturn(permission);
        when(permission.readable(loaded)).thenReturn(List.of(a));
        when(mapper.toReadableResponses(List.of(a), 1L, permission)).thenReturn(List.of(cardA));

        assertEquals(List.of(cardA), reader.refreshCards(1L, List.of("a", "missing", "b", "a")));

        var query = ArgumentCaptor.forClass(Query.class);
        verify(store).find(query.capture(), eq(Post.class));
        assertEquals(3, query.getValue().limit());
        verify(access).forPosts(loaded, 1L);
        verify(reposts).attachRecentReposters(List.of(cardA));
        verifyNoMoreInteractions(store, access);
    }

    @Test
    void databaseOrderCannotReorderTheCachedTimeline() {
        Post a = new Post(); Post b = new Post();
        List<Post> loaded = List.of(b, a);
        var permission = mock(ContentAccessService.AccessBatch.class);
        PostResponse cardA = new PostResponse(); cardA.setId("a");
        PostResponse cardB = new PostResponse(); cardB.setId("b");
        when(store.find(any(Query.class), eq(Post.class))).thenReturn(loaded);
        when(access.forPosts(loaded, 1L)).thenReturn(permission);
        when(permission.readable(loaded)).thenReturn(loaded);
        when(mapper.toReadableResponses(loaded, 1L, permission)).thenReturn(List.of(cardB, cardA));
        assertEquals(List.of(cardA, cardB), reader.refreshCards(1L, List.of("a", "b")));
    }

    @Test
    void emptyAndOversizedPagesCannotBecomeUnboundedReads() {
        assertTrue(reader.refreshCards(1L, List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reader.refreshCards(1L,
                IntStream.range(0, 31).mapToObj(String::valueOf).toList()));
        verifyNoInteractions(store, access, mapper, reposts);
    }
}
