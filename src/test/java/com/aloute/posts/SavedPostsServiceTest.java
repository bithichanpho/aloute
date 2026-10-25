package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.SavedPost;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.api.PostCards;
import com.aloute.posts.application.SavedPostsService;
import com.aloute.posts.domain.PostAccess;
import java.util.List;
import org.junit.jupiter.api.Test;

class SavedPostsServiceTest {
    private final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    private final PostAccess access = mock(PostAccess.class);
    private final PostCards cards = mock(PostCards.class);
    private final SavedPostsService service = new SavedPostsService(store, access, cards);

    @Test
    void unauthenticatedCallsCannotWriteOrReadSavedState() {
        assertThrows(com.aloute.common.exception.UnauthorizedException.class, () -> service.save(null, "p", false));
        assertThrows(com.aloute.common.exception.UnauthorizedException.class, () -> service.saved(null, 0));
        verifyNoInteractions(store, access, cards);
    }

    @Test
    void hiddenPostCannotBeSaved() {
        doThrow(new ResourceNotFoundException("hidden")).when(access).requireReadable("p", 1L);
        assertThrows(ResourceNotFoundException.class, () -> service.save(1L, "p", true));
        verifyNoInteractions(store, cards);
    }

    @Test
    void saveKeepsExistingViewerScopedIdAndUnsaveDoesNotRequireReadableDeletedPost() {
        service.save(1L, "p", true);
        verify(store).save(argThat((SavedPost saved) -> "1:p".equals(saved.getId())
                && saved.getUserId().equals(1L) && saved.getPostId().equals("p")));
        clearInvocations(access);
        service.save(1L, "p", false);
        verify(store).remove(any(Query.class), eq(SavedPost.class));
        verifyNoInteractions(access);
    }

    @Test
    void savedPageUsesBoundedDatabasePaginationAndOneAuthorizedCardBatch() {
        SavedPost first = new SavedPost(); first.setPostId("p1");
        SavedPost second = new SavedPost(); second.setPostId("p2");
        when(store.find(any(), eq(SavedPost.class))).thenReturn(List.of(first, second));
        PostResponse readable = new PostResponse(); readable.setId("p2");
        when(cards.getPostsByIds(List.of("p1", "p2"), 1L)).thenReturn(List.of(readable));

        assertEquals(List.of(readable), service.saved(1L, 2));

        verify(store).find(argThat(query -> query.skip() == 40 && query.limit() == 20), eq(SavedPost.class));
        verify(cards).getPostsByIds(List.of("p1", "p2"), 1L);
        verifyNoMoreInteractions(cards);
    }
}
