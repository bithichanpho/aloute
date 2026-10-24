package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModerationRetryPolicyTest {
    @Test
    void retryCountIsCappedAndBackoffIsBounded() {
        ModerationRetryPolicy policy = new ModerationRetryPolicy(5, 30);
        assertFalse(policy.exhausted(4));
        assertTrue(policy.exhausted(5));
        assertEquals(30, policy.delaySeconds(1));
        assertEquals(60, policy.delaySeconds(2));
        assertEquals(120, policy.delaySeconds(3));
        assertEquals(240, policy.delaySeconds(4));
        assertEquals(300, policy.delaySeconds(5));
    }

    @Test
    void permanentUpstreamRejectionIsNotRetried() {
        ModerationRetryPolicy policy = new ModerationRetryPolicy(5, 30);
        assertFalse(policy.isRetryable(ModerationFailureException.fromUpstreamStatus(422, "media")));
        assertFalse(policy.isRetryable(ModerationFailureException.fromUpstreamStatus(413, "media")));
        assertTrue(policy.isRetryable(ModerationFailureException.fromUpstreamStatus(429, "text")));
        assertTrue(policy.isRetryable(ModerationFailureException.fromUpstreamStatus(503, "text")));
    }
}
