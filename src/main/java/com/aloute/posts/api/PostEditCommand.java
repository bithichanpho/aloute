package com.aloute.posts.api;

/** Immutable internal command; the REST adapter keeps its existing validated request DTO. */
public record PostEditCommand(String content, String imageUrl, String videoUrl, String visibility) {
    public PostEditCommand {
        if (visibility == null || !java.util.Set.of("PUBLIC", "FRIENDS", "CLOSE_FRIENDS", "ONLY_ME").contains(visibility)) {
            throw new IllegalArgumentException("Invalid post visibility");
        }
    }
}
