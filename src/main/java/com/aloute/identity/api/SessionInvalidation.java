package com.aloute.identity.api;

import java.time.Instant;

/** No caller can access refresh-token/password persistence through this port. */
public interface SessionInvalidation {
    void revokeAll(Long userId, Instant revokedAt, String username);
}
