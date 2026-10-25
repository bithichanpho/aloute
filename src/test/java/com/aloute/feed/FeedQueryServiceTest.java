package com.aloute.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.dto.response.PostResponse;
import com.aloute.feed.application.FeedQueryService;
import com.aloute.posts.api.FeedPostReader;
import com.aloute.posts.api.FeedPostReader.Candidate;
import com.aloute.posts.api.FeedPostReader.Position;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FeedQueryServiceTest {
    private final FeedPostReader posts = mock(FeedPostReader.class);
    private final FeedQueryService service = new FeedQueryService(posts);
    private final LocalDateTime time = LocalDateTime.of(2026, 10, 24, 10, 0);

    @Test
    void invisibleCandidatesAdvanceCursorWithoutSkippingNextVisibleCard() {
        Candidate hidden = candidate("3", false);
        Candidate first = candidate("2", true);
        Candidate second = candidate("1", true);
        when(posts.readBatch(eq(9L), any(), eq(false), eq(false), eq(""), isNull(), eq(50)))
                .thenReturn(List.of(hidden, first, second));
        when(posts.readBatch(eq(9L), any(), eq(false), eq(false), eq(""), eq(first.position()), eq(50)))
                .thenReturn(List.of(second));

        var page1 = service.getCursorFeed(9L, List.of(), "foryou", null, null, 1);
        assertEquals(List.of("2"), page1.items().stream().map(PostResponse::getId).toList());
        assertTrue(page1.hasMore());
        assertEquals(time + "|2", new String(Base64.getUrlDecoder().decode(page1.nextCursor()), StandardCharsets.UTF_8));
        var page2 = service.getCursorFeed(9L, List.of(), "foryou", null, page1.nextCursor(), 1);
        assertEquals(List.of("1"), page2.items().stream().map(PostResponse::getId).toList());
        assertFalse(page2.hasMore());
    }

    @Test
    void invalidCursorOrHashtagCannotReachPersistencePort() {
        assertThrows(BadRequestException.class,
                () -> service.getCursorFeedForViewer(9L, "foryou", null, "invalid!", 20));
        assertThrows(BadRequestException.class,
                () -> service.getCursorFeedForViewer(9L, "foryou", "bad tag", null, 20));
        verifyNoInteractions(posts);
    }

    @Test
    void followingUsesServerSideGraphAndIncludesViewer() {
        when(posts.readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyInt()))
                .thenReturn(List.of());
        service.getCursorFeedForViewer(9L, "following", "#ALOUTE", null, 20);
        verify(posts).readBatch(9L, List.of("9"), true, true, "aloute", null, 80);
    }

    @Test
    void sparsePrivateFeedStopsAfterTwentyBoundedReadsAndKeepsContinuation() {
        var hidden = IntStream.range(0, 50).mapToObj(i -> candidate(String.valueOf(i), false)).toList();
        when(posts.readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), eq(50))).thenReturn(hidden);
        var page = service.getCursorFeedForViewer(9L, "foryou", null, null, 1);
        assertTrue(page.items().isEmpty());
        assertTrue(page.hasMore());
        assertFalse(page.nextCursor().isBlank());
        verify(posts, times(20)).readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), eq(50));
    }

    @Test
    void oversizedRequestIsClampedAndOnlyConsumedCardsAreEnriched() {
        var candidates = IntStream.range(0, 50).mapToObj(i -> candidate(String.valueOf(i), true)).toList();
        when(posts.readBatch(any(), any(), anyBoolean(), anyBoolean(), any(), any(), eq(120)))
                .thenReturn(candidates);
        var page = service.getCursorFeedForViewer(9L, "foryou", null, null, 1000);
        assertEquals(30, page.items().size());
        verify(posts).attachRecentReposters(page.items());
    }

    private Candidate candidate(String id, boolean readable) {
        PostResponse response = new PostResponse();
        response.setId(id);
        return new Candidate(new Position(time, id), readable ? response : null);
    }
}
