package com.aloute.feed.application;

import com.aloute.dto.response.FeedCursorPage;
import com.aloute.dto.response.PostResponse;
import com.aloute.feed.api.FeedQuery;
import com.aloute.posts.api.FeedPostReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Every cache hit passes through Posts authorization before any card leaves Feed. */
@Service
@Primary
public class FeedQueryService implements FeedQuery {
    private final FeedPostReader posts;
    private final FeedPageQuery pages;

    @Autowired
    public FeedQueryService(FeedPostReader posts, FeedPageQuery pages) {
        this.posts = posts;
        this.pages = pages;
    }

    /** Compatibility for direct callers without Spring caching. */
    public FeedQueryService(FeedPostReader posts) {
        this(posts, new FeedPageQuery(posts));
    }

    public FeedCursorPage getCursorFeedForViewer(Long viewerId, String tab, String hashtag, String cursor, int size) {
        AtomicBoolean queried = new AtomicBoolean();
        return authorizeCached(pages.getCursorFeedForViewer(viewerId, tab, hashtag, cursor, size, queried),
                viewerId, queried, "following".equalsIgnoreCase(tab == null ? "" : tab.trim()));
    }

    public FeedCursorPage getCursorFeed(Long viewerId, List<Long> followingIds,
            String tab, String hashtag, String cursor, int size) {
        AtomicBoolean queried = new AtomicBoolean();
        return authorizeCached(pages.getCursorFeed(viewerId, followingIds, tab, hashtag, cursor, size, queried),
                viewerId, queried, "following".equalsIgnoreCase(tab == null ? "" : tab.trim()));
    }

    private FeedCursorPage authorizeCached(FeedCursorPage page, Long viewerId, AtomicBoolean queried, boolean followingOnly) {
        // Set only by the executed query body. A separate pre-cache lookup would race with population.
        // A miss has already used current authorization; hits reload cards in one bounded batch.
        if (queried.get() || page.items().isEmpty()) return page;
        List<String> ids = page.items().stream().map(PostResponse::getId).toList();
        List<PostResponse> cards = followingOnly ? posts.refreshFollowingCards(viewerId, ids) : posts.refreshCards(viewerId, ids);
        return new FeedCursorPage(cards, page.nextCursor(), page.hasMore());
    }
}
