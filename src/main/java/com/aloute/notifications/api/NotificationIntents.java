package com.aloute.notifications.api;

import java.time.LocalDateTime;

/** The PostgreSQL outbox is the single durable delivery mechanism for activity notifications. */
public interface NotificationIntents {
    void enqueue(Long receiverId, Long senderId, String type, String referenceId, String content);

    /** Persist an intent in a fresh transaction when called from an after-commit callback. */
    default void enqueueAfterCommit(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        enqueue(receiverId, senderId, type, referenceId, content);
    }

    /** Checkpoint that the in-app WebSocket event was emitted, independently of FCM retries. */
    default void markInAppDelivered(String eventId, LocalDateTime deliveredAt) {
        // Compatibility for lightweight test doubles; the production adapter persists this marker.
    }
}
