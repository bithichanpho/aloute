package com.aloute.notifications.api;

/** The existing PostgreSQL outbox is the single durable delivery mechanism. */
public interface NotificationIntents {
    void enqueue(Long receiverId, Long senderId, String type, String referenceId, String content);
}
