package com.aloute.service;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Fail-closed moderation: an unavailable or malformed moderator never means approved. */
@Service
public class ModerationService implements com.aloute.moderation.api.ModerationPolicy {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String serviceUrl;
    private final Duration readTimeout;
    private final Duration textTimeout;
    private final Duration uploadMediaTimeout;
    private final boolean enabled;
    private final String apiToken;

    /** Convenience constructors retained for focused unit tests. */
    public ModerationService(String serviceUrl) {
        this(serviceUrl, Duration.ofSeconds(2), Duration.ofSeconds(45), Duration.ofSeconds(10),
                Duration.ofSeconds(12), true, "", JsonMapper.builder().build());
    }

    public ModerationService(String serviceUrl, Duration connectTimeout, Duration readTimeout) {
        this(serviceUrl, connectTimeout, readTimeout, Duration.ofSeconds(10),
                shorterOf(readTimeout, Duration.ofSeconds(12)), true, "", JsonMapper.builder().build());
    }

    public ModerationService(String serviceUrl, Duration connectTimeout, Duration readTimeout, boolean enabled) {
        this(serviceUrl, connectTimeout, readTimeout, Duration.ofSeconds(10),
                shorterOf(readTimeout, Duration.ofSeconds(12)), enabled, "", JsonMapper.builder().build());
    }

    /** Test constructor with an explicit service token. */
    public ModerationService(String serviceUrl, Duration connectTimeout, Duration readTimeout,
                             boolean enabled, String apiToken) {
        this(serviceUrl, connectTimeout, readTimeout, Duration.ofSeconds(10),
                shorterOf(readTimeout, Duration.ofSeconds(12)), enabled, apiToken, JsonMapper.builder().build());
    }

    public ModerationService(String serviceUrl, Duration connectTimeout, Duration readTimeout,
                             boolean enabled, String apiToken, JsonMapper jsonMapper) {
        this(serviceUrl, connectTimeout, readTimeout, shorterOf(readTimeout, Duration.ofSeconds(10)),
                shorterOf(readTimeout, Duration.ofSeconds(12)), enabled, apiToken, jsonMapper);
    }

    @Autowired
    public ModerationService(
            @Value("${app.moderation.service-url:http://localhost:8000}") String serviceUrl,
            @Value("${app.moderation.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.moderation.read-timeout:60s}") Duration readTimeout,
            @Value("${app.moderation.text-timeout:10s}") Duration textTimeout,
            @Value("${app.moderation.upload-media-timeout:12s}") Duration uploadMediaTimeout,
            @Value("${app.moderation.enabled:true}") boolean enabled,
            @Value("${app.moderation.api-token:}") String apiToken,
            JsonMapper jsonMapper) {
        this.serviceUrl = stripTrailingSlash(serviceUrl);
        this.enabled = enabled;
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.jsonMapper = jsonMapper == null ? JsonMapper.builder().build() : jsonMapper;
        if (!enabled) {
            log.warn("Content moderation is disabled by configuration; non-post media uploads fail closed");
        } else {
            log.info("Content moderation enabled; upstream endpoint={}", this.serviceUrl);
        }
        Duration safeConnectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(2));
        this.readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(45));
        this.textTimeout = positiveOrDefault(textTimeout, Duration.ofSeconds(10));
        this.uploadMediaTimeout = shorterOf(uploadMediaTimeout, Duration.ofSeconds(15));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(safeConnectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
 
    private static Duration shorterOf(Duration configured, Duration cap) {
        Duration safe = positiveOrDefault(configured, cap);
        return safe.compareTo(cap) < 0 ? safe : cap;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:8000";
        return value.replaceAll("/+$", "");
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ModerationResult check(String content) {
        if (!enabled) return new ModerationResult("disabled-demo-mode", 0.0);
        // Do not send empty or oversized payloads to FastAPI: they are rejected with HTTP 422.
        // Returning a user-input error here makes the real cause visible instead of misreporting
        // the moderation service as unavailable.
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nội dung cần kiểm duyệt không được để trống.");
        }
        if (content.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nội dung vượt quá giới hạn kiểm duyệt 20.000 ký tự.");
        }
        try {
            Map<?, ?> response = postJson("/moderate", "{\"text\":" + jsonString(content) + "}", textTimeout);
            return parseResult(response, "text");
        } catch (ModerationHttpException ex) {
            logRejectedRequest("Text", ex);
            throw ModerationFailureException.fromUpstreamStatus(ex.statusCode(), "text");
        } catch (RestClientException ex) {
            log.warn("Text moderation unavailable at {} ({})", serviceUrl, ex.getClass().getSimpleName());
            throw ModerationFailureException.transientFailure(
                    "TEXT_SERVICE_UNAVAILABLE", "Text moderation service is temporarily unavailable", ex);
        }
    }

    public ModerationResult checkMedia(String url, String mediaType) {
        return checkMedia(url, mediaType, readTimeout);
    }

    /** Upload path uses a shorter hard deadline so a video cannot hold a Tomcat request for 60s. */
    public ModerationResult checkMediaForUpload(String url, String mediaType) {
        return checkMedia(url, mediaType, uploadMediaTimeout);
    }

    private ModerationResult checkMedia(String url, String mediaType, Duration requestTimeout) {
        if (!enabled) return new ModerationResult("disabled-demo-mode", 0.0);
        if (url == null || url.isBlank()) return new ModerationResult("safe", 0.0);
        if (!"image".equals(mediaType) && !"video".equals(mediaType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại nội dung media không hợp lệ.");
        }
        try {
            String requestJson = "{\"url\":" + jsonString(url)
                    + ",\"mediaType\":" + jsonString(mediaType) + "}";
            Map<?, ?> response = postJson("/moderate-media", requestJson, requestTimeout);
            return parseResult(response, "media");
        } catch (ModerationHttpException ex) {
            logRejectedRequest("Media", ex);
            throw ModerationFailureException.fromUpstreamStatus(ex.statusCode(), "media");
        } catch (RestClientException ex) {
            log.warn("Media moderation unavailable at {} ({})", serviceUrl, ex.getClass().getSimpleName());
            throw ModerationFailureException.transientFailure(
                    "MEDIA_SERVICE_UNAVAILABLE", "Media moderation service is temporarily unavailable", ex);
        }
    }

    /**
     * Send raw UTF-8 JSON through java.net.http.HttpClient with a known body length.
     * This deliberately bypasses Spring's HTTP message converters because the previous
     * adapter reached FastAPI with a missing body (HTTP 422: loc=["body"]).
     */
    private Map<?, ?> postJson(String path, String json, Duration requestTimeout) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        URI uri;
        try {
            uri = URI.create(serviceUrl + (path.startsWith("/") ? path : "/" + path));
        } catch (IllegalArgumentException ex) {
            throw ModerationFailureException.permanentFailure(
                    "INVALID_SERVICE_URL", "Moderation service URL is invalid", ex);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (!apiToken.isBlank()) {
            requestBuilder.header("X-Moderation-Token", apiToken);
        }

        final HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RestClientException("Interrupted while calling moderation service", ex);
        } catch (IOException ex) {
            throw new ResourceAccessException("I/O error calling moderation service", ex);
        }

        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModerationHttpException(response.statusCode(), responseBody);
        }
        try {
            Map<?, ?> result = jsonMapper.readValue(responseBody, Map.class);
            if (result == null) {
                throw new IllegalStateException("Empty moderation result");
            }
            return result;
        } catch (Exception ex) {
            throw ModerationFailureException.transientFailure(
                    "INVALID_JSON_RESPONSE", "Moderation service returned an invalid JSON response", ex);
        }
    }

    private static final class ModerationHttpException extends RestClientException {
        private final int statusCode;
        private final String responseBody;

        private ModerationHttpException(int statusCode, String responseBody) {
            super("Moderation service returned HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        int statusCode() { return statusCode; }
        String responseBody() { return responseBody; }
    }

    /** Minimal JSON string escaping; preserves Unicode and escapes JSON control characters. */
    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    /** Log only the moderation service's error response, never submitted content or credentials. */
    private void logRejectedRequest(String operation, ModerationHttpException ex) {
        String detail = ex.responseBody();
        if (detail == null || detail.isBlank()) detail = "(empty response body)";
        detail = detail.replace('\r', ' ').replace('\n', ' ');
        if (detail.length() > 400) detail = detail.substring(0, 400) + "...";
        log.warn("{} moderation service rejected the request: status={}, detail={}",
                operation, ex.statusCode(), detail);
    }

    private ModerationResult parseResult(Map<?, ?> response, String type) {
        if (response == null || !(response.get("score") instanceof Number scoreValue)
                || response.get("label") == null) {
            log.warn("Moderation service returned an invalid {} response", type);
            throw ModerationFailureException.transientFailure(
                    "INVALID_" + type.toUpperCase(java.util.Locale.ROOT) + "_RESPONSE",
                    "Moderation service returned an invalid response", null);
        }
        double score = scoreValue.doubleValue();
        String label = response.get("label").toString().trim();
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0 || label.isBlank()
                || "unknown".equalsIgnoreCase(label)) {
            log.warn("Moderation service returned an unknown/invalid {} result", type);
            throw ModerationFailureException.transientFailure(
                    "INVALID_" + type.toUpperCase(java.util.Locale.ROOT) + "_RESULT",
                    "Moderation service returned an invalid result", null);
        }
        return new ModerationResult(label, score);
    }

    public boolean isMediaApproved(String url, String mediaType) {
        return isMediaApproved(url, mediaType, false);
    }

    /**
     * Preserve the full moderation decision for media in the asynchronous post pipeline.
     * Upload admission can continue using isMediaApprovedForUpload(), while post moderation
     * distinguishes FLAGGED (eligible for ordinary visibility and optional review) from HIDDEN.
     */
    public ModerationStatus resolveMediaStatus(String url, String mediaType) {
        if (url == null || url.isBlank()) return ModerationStatus.APPROVED;
        ModerationResult result = checkMedia(url, mediaType);
        requireKnownResult(result);
        return resolveStatus(result);
    }

    public boolean isMediaApprovedForUpload(String url, String mediaType) {
        return isMediaApproved(url, mediaType, true);
    }

    private boolean isMediaApproved(String url, String mediaType, boolean uploadRequest) {
        if (url == null || url.isBlank()) return true;
        ModerationResult result = uploadRequest
                ? checkMediaForUpload(url, mediaType)
                : checkMedia(url, mediaType);
        requireKnownResult(result);
        return resolveStatus(result.getScore()) == ModerationStatus.APPROVED;
    }

    public ModerationStatus resolveStatus(ModerationResult result) {
        requireKnownResult(result);
        return resolveStatus(result.getScore());
    }

    /** Retained for callers/tests that already have a validated numeric score. */
    public ModerationStatus resolveStatus(double toxicityScore) {
        if (!Double.isFinite(toxicityScore) || toxicityScore < 0.0 || toxicityScore > 1.0) {
            throw unavailable();
        }
        if (toxicityScore >= 0.8) return ModerationStatus.HIDDEN;
        if (toxicityScore >= 0.5) return ModerationStatus.FLAGGED;
        return ModerationStatus.APPROVED;
    }

    private void requireKnownResult(ModerationResult result) {
        if (result == null || result.getLabel() == null || result.getLabel().isBlank()
                || "unknown".equalsIgnoreCase(result.getLabel())
                || !Double.isFinite(result.getScore())
                || result.getScore() < 0.0 || result.getScore() > 1.0) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Dịch vụ kiểm duyệt hiện không khả dụng. Nội dung chưa được đăng; vui lòng thử lại sau.");
    }
}
