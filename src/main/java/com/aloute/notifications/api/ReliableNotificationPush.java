package com.aloute.notifications.api;

/** Transient adapter failures must propagate to the durable outbox worker. */
public interface ReliableNotificationPush {
    void sendNotificationToUserReliably(Long userId, String title, String body, String relativeUrl);
}
