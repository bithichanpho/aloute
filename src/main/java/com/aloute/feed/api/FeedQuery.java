package com.aloute.feed.api;

import com.aloute.dto.response.FeedCursorPage;
import java.util.List;

public interface FeedQuery {
    FeedCursorPage getCursorFeedForViewer(Long viewerId, String tab, String hashtag, String cursor, int size);
    FeedCursorPage getCursorFeed(Long viewerId, List<Long> followingIds,
            String tab, String hashtag, String cursor, int size);
}
