package com.aloute.config;

import java.util.Arrays;
import java.util.List;

/** Shared validation for credentialed HTTP CORS and WebSocket origins. */
final class AllowedOriginPolicy {
    private AllowedOriginPolicy() {}

    static List<String> parse(String configuredOrigins) {
        List<String> origins = Arrays.stream(
                        configuredOrigins == null ? new String[0] : configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("APP_CORS_ALLOWED_ORIGINS must contain at least one origin");
        }
        if (origins.stream().anyMatch("*"::equals)) {
            throw new IllegalStateException(
                    "APP_CORS_ALLOWED_ORIGINS cannot contain '*' when credential cookies are enabled");
        }
        return origins;
    }
}
