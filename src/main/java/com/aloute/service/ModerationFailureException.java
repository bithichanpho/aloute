package com.aloute.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A classified moderation failure. Retry policy is explicit; it never implies approval. */
public final class ModerationFailureException extends ResponseStatusException {
    private final boolean retryable;
    private final int upstreamStatus;
    private final String failureCode;

    private ModerationFailureException(boolean retryable, int upstreamStatus,
            String failureCode, String message, Throwable cause) {
        super(retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, message, cause);
        this.retryable = retryable;
        this.upstreamStatus = upstreamStatus;
        this.failureCode = sanitizeCode(failureCode);
    }

    public static ModerationFailureException fromUpstreamStatus(int status, String operation) {
        boolean transientStatus = status == 408 || status == 425 || status == 429 || status >= 500;
        String operationCode = operation == null ? "REQUEST" : operation.toUpperCase(java.util.Locale.ROOT);
        return new ModerationFailureException(
                transientStatus,
                status,
                "UPSTREAM_HTTP_" + status,
                "Moderation " + operationCode.toLowerCase(java.util.Locale.ROOT)
                        + " service returned HTTP " + status,
                null);
    }

    public static ModerationFailureException transientFailure(String code, String message, Throwable cause) {
        return new ModerationFailureException(true, 0, code, message, cause);
    }

    public static ModerationFailureException permanentFailure(String code, String message, Throwable cause) {
        return new ModerationFailureException(false, 0, code, message, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /** Safe short code suitable for logs and admin review; never includes submitted content. */
    public String getFailureCode() {
        return failureCode;
    }

    private static String sanitizeCode(String value) {
        if (value == null || value.isBlank()) return "MODERATION_FAILURE";
        String normalized = value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
