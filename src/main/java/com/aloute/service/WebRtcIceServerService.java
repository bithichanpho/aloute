package com.aloute.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces public STUN entries and short-lived authenticated TURN REST credentials. */
@Service
public class WebRtcIceServerService {
    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final String turnSharedSecret;
    private final long credentialTtlSeconds;

    public WebRtcIceServerService(
            @Value("${app.webrtc.stun-urls:stun:stun.l.google.com:19302}") String stunUrls,
            @Value("${app.webrtc.turn-urls:}") String turnUrls,
            @Value("${app.webrtc.turn-shared-secret:}") String turnSharedSecret,
            @Value("${app.webrtc.turn-credential-ttl-seconds:3600}") long credentialTtlSeconds) {
        this.stunUrls = parseUrls(stunUrls);
        this.turnUrls = parseUrls(turnUrls);
        this.turnSharedSecret = turnSharedSecret == null ? "" : turnSharedSecret.trim();
        this.credentialTtlSeconds = Math.max(300, Math.min(86_400, credentialTtlSeconds));
        if (!this.turnUrls.isEmpty() && this.turnSharedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "APP_WEBRTC_TURN_URLS requires a TURN_SHARED_SECRET of at least 32 UTF-8 bytes matching the configured TURN server");
        }
        if (this.stunUrls.isEmpty() && this.turnUrls.isEmpty()) {
            throw new IllegalStateException("At least one STUN_URL or TURN_URL must be configured");
        }
    }

    public List<Map<String, Object>> iceServersForUser(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException("Authenticated principal is required for ICE credentials");
        }
        List<Map<String, Object>> servers = new ArrayList<>();
        if (!stunUrls.isEmpty()) {
            Map<String, Object> stun = new LinkedHashMap<>();
            stun.put("urls", stunUrls);
            servers.add(stun);
        }
        if (!turnUrls.isEmpty()) {
            long expiresAt = Instant.now().plusSeconds(credentialTtlSeconds).getEpochSecond();
            String username = expiresAt + ":" + opaqueUserId(principalName);
            String credential = hmacSha1Base64(turnSharedSecret, username);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("urls", turnUrls);
            turn.put("username", username);
            turn.put("credential", credential);
            servers.add(turn);
        }
        return List.copyOf(servers);
    }

    private static List<String> parseUrls(String values) {
        if (values == null || values.isBlank()) return List.of();
        return java.util.Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String opaqueUserId(String principalName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(principalName.getBytes(StandardCharsets.UTF_8));
            // Avoid exposing raw usernames to the TURN service while retaining per-user separation.
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String hmacSha1Base64(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot generate TURN credentials", ex);
        }
    }
}
