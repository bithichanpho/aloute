package com.aloute.model.document;

/** PENDING content is visible only to its author until asynchronous moderation completes. */
public enum ModerationStatus {
    PENDING,
    APPROVED,
    FLAGGED,
    HIDDEN
}
