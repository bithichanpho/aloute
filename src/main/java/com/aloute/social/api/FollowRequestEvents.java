package com.aloute.social.api;

/** Best-effort UI hints after commit; persisted requests and polling remain authoritative. */
@FunctionalInterface
public interface FollowRequestEvents {
    void publish(Long recipientUserId, String eventType, Long followerId, Long targetId, String status);
}
