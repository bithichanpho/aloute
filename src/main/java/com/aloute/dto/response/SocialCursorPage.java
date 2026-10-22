package com.aloute.dto.response;

import java.util.List;

/** Opaque-cursor page for PostgreSQL-backed social relationship lists. */
public record SocialCursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public SocialCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
