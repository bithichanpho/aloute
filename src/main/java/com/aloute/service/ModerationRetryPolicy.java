package com.aloute.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shared bounded retry policy for post and comment moderation queues. */
@Component
public class ModerationRetryPolicy {
    private final int maxAttempts;
    private final long baseDelaySeconds;

    public ModerationRetryPolicy(
            @Value("${app.moderation.max-attempts:5}") int maxAttempts,
            @Value("${app.moderation.retry-base-delay-seconds:30}") long baseDelaySeconds) {
        this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        this.baseDelaySeconds = Math.max(5, Math.min(300, baseDelaySeconds));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Exponential delay, capped at five minutes. attempts is the 1-based attempt number. */
    public long delaySeconds(int attempts) {
        int exponent = Math.max(0, Math.min(4, attempts - 1));
        return Math.min(300, baseDelaySeconds * (1L << exponent));
    }

    public boolean isRetryable(Throwable failure) {
        if (failure instanceof ModerationFailureException moderationFailure) {
            return moderationFailure.isRetryable();
        }
        if (failure instanceof ResponseStatusException statusException) {
            return statusException.getStatusCode().is5xxServerError();
        }
        // Unknown infrastructure/runtime failures are transient initially, but still bounded.
        return true;
    }

    public String failureCode(Throwable failure) {
        if (failure instanceof ModerationFailureException moderationFailure) {
            return moderationFailure.getFailureCode();
        }
        if (failure instanceof ResponseStatusException statusException) {
            return "HTTP_" + statusException.getStatusCode().value();
        }
        if (failure == null) return "UNKNOWN_FAILURE";
        return "INTERNAL_" + failure.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }
}
