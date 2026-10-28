package com.aloute.model.document;

/**
 * Moderation's automatic result for user-generated content.
 * PENDING is visible only to its author. APPROVED and successful FLAGGED results follow
 * normal audience/privacy rules. HIDDEN is not publicly readable. FLAGGED rows with a
 * moderation failure reason remain fail-closed until an automatic retry or secondary review.
 */
public enum ModerationStatus {
    PENDING,
    APPROVED,
    FLAGGED,
    HIDDEN
}
