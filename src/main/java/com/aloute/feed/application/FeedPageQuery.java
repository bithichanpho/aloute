package com.aloute.feed.application;

import com.aloute.posts.api.FeedPostReader;
import com.aloute.posts.api.FeedPostReader.Position;
import com.aloute.dto.response.FeedCursorPage;
import com.aloute.dto.response.PostResponse;
import com.aloute.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;

/** Internal cache boundary. Values and keys retain the existing feedPages format. */
@Service
public class FeedPageQuery {
    private final FeedPostReader posts;

    public FeedPageQuery(FeedPostReader posts) { this.posts = posts; }

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "feedPages",
            key = "T(com.aloute.service.FeedCacheKey).createForViewer(#viewerId, #tab, #hashtag, #cursor, #size)",
            condition = "#viewerId != null", sync = true)
    public FeedCursorPage getCursorFeedForViewer(Long viewerId, String tab, String hashtag, String cursor, int size, java.util.concurrent.atomic.AtomicBoolean queried) {
        queried.set(true);
        return read(viewerId, null, true, tab, hashtag, cursor, size);
    }

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "feedPages",
            key = "T(com.aloute.service.FeedCacheKey).create(#viewerId, #followingIds, #tab, #hashtag, #cursor, #size)",
            condition = "#viewerId != null", sync = true)
    public FeedCursorPage getCursorFeed(Long viewerId, List<Long> followingIds,
            String tab, String hashtag, String cursor, int size, java.util.concurrent.atomic.AtomicBoolean queried) {
        queried.set(true);
        return read(viewerId, followingIds, false, tab, hashtag, cursor, size);
    }

    private FeedCursorPage read(Long viewerId, List<Long> followingIds, boolean streamFollowing,
            String tab, String hashtag, String encodedCursor, int requestedSize) {
        int size = Math.max(1, Math.min(30, requestedSize));
        boolean followingOnly = "following".equalsIgnoreCase(tab);
        List<String> followed = followingIds == null ? new ArrayList<>()
                : followingIds.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (viewerId != null && !followed.contains(String.valueOf(viewerId))) followed.add(String.valueOf(viewerId));
        if (followingOnly && followed.isEmpty()) return new FeedCursorPage(List.of(), "", false);

        String normalizedTag = normalizeHashtag(hashtag);
        Position cursor = decodeFeedCursor(encodedCursor);
        String nextCursor = encodedCursor == null ? "" : encodedCursor;
        List<PostResponse> result = new ArrayList<>(size);
        final int batchSize = Math.min(150, Math.max(50, size * 4));
        boolean hasMore = false;
        int batches = 0;
        while (result.size() < size && batches < 20) {
            var loaded = posts.readBatch(viewerId, followed, streamFollowing, followingOnly, normalizedTag, cursor, batchSize);
            if (loaded.isEmpty()) { hasMore = false; break; }
            boolean stoppedAtPageBoundary = false;
            for (int i = 0; i < loaded.size(); i++) {
                var candidate = loaded.get(i);
                if (candidate.card() != null) result.add(candidate.card());
                cursor = candidate.position();
                nextCursor = encodeCursor(cursor.createdAt(), cursor.id());
                if (result.size() >= size) {
                    hasMore = i < loaded.size() - 1 || loaded.size() == batchSize;
                    stoppedAtPageBoundary = true;
                    break;
                }
            }
            if (stoppedAtPageBoundary) break;
            if (loaded.size() < batchSize) { hasMore = false; break; }
            hasMore = true;
            batches++;
        }
        if (batches >= 20 && result.size() < size) hasMore = true;
        if (result.isEmpty() && !hasMore) nextCursor = "";
        posts.attachRecentReposters(result);
        return new FeedCursorPage(result, hasMore ? nextCursor : (result.isEmpty() ? "" : nextCursor), hasMore);
    }

    private String normalizeHashtag(String hashtag) {
        if (hashtag == null || hashtag.isBlank()) return "";
        String value = hashtag.trim().replaceFirst("^#", "").toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[\\p{L}\\p{N}_]{1,50}"))
            throw new BadRequestException("Hashtag không hợp lệ");
        return value;
    }

    private String encodeCursor(LocalDateTime createdAt, String id) {
        String value = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Position decodeFeedCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int divider = decoded.indexOf('|');
            if (divider <= 0 || divider == decoded.length() - 1) throw new IllegalArgumentException();
            LocalDateTime createdAt = LocalDateTime.parse(decoded.substring(0, divider));
            String id = decoded.substring(divider + 1);
            if (id.length() > 100) throw new IllegalArgumentException();
            return new Position(createdAt, id);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Cursor feed không hợp lệ");
        }
    }

}
