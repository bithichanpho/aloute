package com.aloute.dto.response;

import java.util.List;

/** Cursor-based page of direct replies for one comment. */
public record CommentCursorPage(List<CommentResponse> items, String nextCursor, boolean hasMore) {
    public CommentCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
