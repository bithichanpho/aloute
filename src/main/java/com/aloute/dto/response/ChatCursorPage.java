package com.aloute.dto.response;

import java.util.List;

public record ChatCursorPage(
        List<ChatMessageResponse> items,
        String nextCursor,
        boolean hasMore) {
}
