package com.aloute.identity.api;

import java.time.Duration;

/** Server-owned abuse protection shared by authorized use cases; exposes no token storage. */
public interface ActionRateLimit {
    void checkAndRecord(String namespace, String subjectKey, int maxRequests,
            Duration window, Duration blockDuration, String blockedMessage, String triggerMessage);
}
