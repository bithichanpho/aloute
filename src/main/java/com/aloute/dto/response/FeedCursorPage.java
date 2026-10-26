package com.aloute.dto.response;

import java.util.List;

/** Stable keyset-paginated feed page. Cursor is opaque to clients. */
public record FeedCursorPage(List<PostResponse> items, String nextCursor, boolean hasMore) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public FeedCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
