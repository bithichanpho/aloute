package com.aloute.posts.api;

/** Immutable edit command with field presence supplied by the validated REST adapter. */
public record PostEditCommand(String content, String imageUrl, String videoUrl, String visibility,
        boolean contentProvided, boolean imageProvided, boolean videoProvided) {
    /** Compatibility adapter for existing full replacement callers. */
    public PostEditCommand(String content, String imageUrl, String videoUrl, String visibility) {
        this(content, imageUrl, videoUrl, visibility, true, true, true);
    }
    public PostEditCommand {
        if (visibility == null || !java.util.Set.of("PUBLIC", "FRIENDS", "CLOSE_FRIENDS", "ONLY_ME").contains(visibility)) {
            throw new IllegalArgumentException("Invalid post visibility");
        }
    }
}
