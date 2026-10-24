package com.aloute.dto.response;

import java.util.List;

/** Stable, bounded page for the conversation inbox. The cursor is opaque to clients. */
public record ConversationCursorPage(
        List<ConversationResponse> items,
        String nextCursor,
        boolean hasMore) {
    public ConversationCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
