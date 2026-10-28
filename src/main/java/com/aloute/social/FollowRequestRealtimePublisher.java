package com.aloute.social;

import com.aloute.users.api.UserDirectory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends authenticated, user-scoped events when a private-account follow request changes. */
@Component
public class FollowRequestRealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(FollowRequestRealtimePublisher.class);
    private final UserDirectory users;
    private final SimpMessagingTemplate messagingTemplate;

    public FollowRequestRealtimePublisher(UserDirectory users, SimpMessagingTemplate messagingTemplate) {
        this.users = users;
        this.messagingTemplate = messagingTemplate;
    }

    /** User-scoped hint only; clients reload the authoritative unread count. */
    public void publishSeen(Long recipientUserId) {
        if (recipientUserId == null) return;
        try {
            users.username(recipientUserId).ifPresent(username -> messagingTemplate.convertAndSendToUser(
                    username, "/queue/follow-requests", Map.of(
                            "eventType", "REQUESTS_SEEN", "targetId", recipientUserId,
                            "occurredAt", Instant.now().toString())));
        } catch (RuntimeException failure) {
            log.warn("Could not publish follow-request inbox seen event (recipientId={}, cause={})",
                    recipientUserId, failure.getClass().getSimpleName());
        }
    }

    public void publish(Long recipientUserId, String eventType, Long followerId, Long targetId, String status) {
        if (recipientUserId == null || eventType == null || followerId == null || targetId == null) return;
        try {
            users.username(recipientUserId).ifPresent(username -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("eventType", eventType);
                payload.put("followerId", followerId);
                payload.put("targetId", targetId);
                payload.put("status", status == null ? "NONE" : status);
                payload.put("followerUsername", users.username(followerId).orElse(""));
                payload.put("occurredAt", Instant.now().toString());
                messagingTemplate.convertAndSendToUser(username, "/queue/follow-requests", payload);
            });
        } catch (RuntimeException failure) {
            // A transient WebSocket/user-directory failure must never roll back a committed follow action.
            log.warn("Could not publish follow-request realtime event (recipientId={}, eventType={}, cause={})",
                    recipientUserId, eventType, failure.getClass().getSimpleName());
        }
    }
}
