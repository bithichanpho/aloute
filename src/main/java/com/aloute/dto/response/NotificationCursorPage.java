package com.aloute.dto.response;

import java.util.List;

public record NotificationCursorPage(
        List<NotificationResponse> items, Long nextCursor, boolean hasMore) {
    public NotificationCursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
