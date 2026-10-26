package com.aloute.dto.response;

import java.util.List;

/** Cursor page loaded only when the user opens the repost audience popover. */
public record ReposterCursorPage(
        List<PostResponse.RepostActor> items,
        String nextCursor,
        boolean hasMore,
        int totalCount) {
    public ReposterCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
        totalCount = Math.max(0, totalCount);
    }
}
