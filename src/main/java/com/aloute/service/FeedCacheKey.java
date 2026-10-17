package com.aloute.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds stable, short cache keys without embedding the complete following list. */
public final class FeedCacheKey {
    private FeedCacheKey() {}

    public static String create(
            Long viewerId,
            List<Long> followingIds,
            String tab,
            String hashtag,
            String cursor,
            int requestedSize) {
        List<Long> normalizedFollowing = followingIds == null
                ? List.of()
                : followingIds.stream().filter(Objects::nonNull).distinct().sorted(Comparator.naturalOrder()).toList();
        String following = normalizedFollowing.stream().map(String::valueOf).collect(Collectors.joining(","));
        String normalizedHashtag = Objects.toString(hashtag, "").trim()
                .replaceFirst("^#", "").toLowerCase(java.util.Locale.ROOT);
        String material = String.join("\n",
                Objects.toString(tab, "").trim().toLowerCase(java.util.Locale.ROOT),
                normalizedHashtag,
                Objects.toString(cursor, "start"),
                Integer.toString(Math.max(1, Math.min(30, requestedSize))),
                following);
        return "cursor:" + Objects.toString(viewerId, "anonymous") + ":" + sha256(material);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
