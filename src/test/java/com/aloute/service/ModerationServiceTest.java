package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aloute.dto.response.ModerationResult;
import com.aloute.model.document.ModerationStatus;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ModerationServiceTest {

    @Test
    void unknownModerationResultFailsClosedWithServiceUnavailable() {
        ModerationService service = new ModerationService("http://localhost:8000");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.resolveStatus(new ModerationResult("unknown", Double.NaN)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }


    @Test
    void unavailableModeratorProducesRetryableFailureInsteadOfUnknownStatus() {
        ModerationService service = new ModerationService(
                "http://127.0.0.1:1", java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(300));

        ModerationFailureException exception = assertThrows(
                ModerationFailureException.class, () -> service.check("test content"));

        assertTrue(exception.isRetryable());
        assertEquals("TEXT_SERVICE_UNAVAILABLE", exception.getFailureCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void upstreamClientErrorIsPermanentButRateLimitIsRetryable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moderate", exchange -> {
            byte[] response = "{\"detail\":\"invalid media URL\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            ModerationService service = new ModerationService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), true, "internal-test-token-123456789");
            ModerationFailureException exception = assertThrows(
                    ModerationFailureException.class, () -> service.check("hello"));
            assertFalse(exception.isRetryable());
            assertEquals("UPSTREAM_HTTP_422", exception.getFailureCode());
        } finally {
            server.stop(0);
        }

        ModerationFailureException limited = ModerationFailureException.fromUpstreamStatus(429, "text");
        assertTrue(limited.isRetryable());
        assertEquals("UPSTREAM_HTTP_429", limited.getFailureCode());
    }

    @Test
    void emptyTextIsRejectedLocallyBeforeCallingModerator() {
        ModerationService service = new ModerationService("http://127.0.0.1:1");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.check("  "));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void oversizedTextIsRejectedLocallyBeforeCallingModerator() {
        ModerationService service = new ModerationService("http://127.0.0.1:1");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.check("x".repeat(20_001)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }


    @Test
    void sendsExplicitUtf8JsonBodyToTextModerationEndpoint() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> contentLength = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/moderate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            token.set(exchange.getRequestHeaders().getFirst("X-Moderation-Token"));
            byte[] response = "{\"label\":\"safe\",\"score\":0.01}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            ModerationService service = new ModerationService(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(2), Duration.ofSeconds(2), true, "a-test-internal-token-that-is-long-enough");

            var result = service.check("Xin chào \"ALOUTE\"\n");

            assertEquals("safe", result.getLabel());
            assertEquals(0.01, result.getScore(), 0.00001);
            assertEquals("{\"text\":\"Xin chào \\\"ALOUTE\\\"\\n\"}", requestBody.get());
            assertTrue(contentType.get().toLowerCase(java.util.Locale.ROOT).startsWith("application/json"));
            assertEquals(Integer.toString(requestBody.get().getBytes(StandardCharsets.UTF_8).length), contentLength.get());
            assertEquals("a-test-internal-token-that-is-long-enough", token.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validLowToxicityScoreCanBeApproved() {
        ModerationService service = new ModerationService("http://localhost:8000");
        assertEquals(
                ModerationStatus.APPROVED,
                service.resolveStatus(new ModerationResult("safe", 0.01)));
    }
    @Test
    void explicitDemoFlagDisablesRemoteCallsAndUsesOnlyTheDemoSafeStatus() {
        ModerationService service = new ModerationService(
                "http://127.0.0.1:1", java.time.Duration.ofMillis(100),
                java.time.Duration.ofMillis(100), false);
        org.junit.jupiter.api.Assertions.assertFalse(service.isEnabled());
        assertEquals(ModerationStatus.APPROVED, service.resolveStatus(service.check("demo content")));
        org.junit.jupiter.api.Assertions.assertTrue(service.isMediaApproved("http://example.invalid/image.png", "image"));
    }

}
