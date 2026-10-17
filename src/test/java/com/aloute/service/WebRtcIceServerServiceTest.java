package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

class WebRtcIceServerServiceTest {
    @Test
    void returnsStunAndShortLivedTurnRestCredentials() throws Exception {
        String secret = "a-random-turn-secret-that-is-at-least-32-bytes-long";
        WebRtcIceServerService service = new WebRtcIceServerService(
                "stun:stun.example.test:3478", "turn:turn.example.test:3478?transport=udp,turn:turn.example.test:3478?transport=tcp",
                secret, 3600);

        List<Map<String, Object>> result = service.iceServersForUser("alice");
        assertEquals(2, result.size());
        assertEquals(List.of("stun:stun.example.test:3478"), result.get(0).get("urls"));
        @SuppressWarnings("unchecked") List<String> turnUrls = (List<String>) result.get(1).get("urls");
        assertEquals(2, turnUrls.size());
        String username = (String) result.get(1).get("username");
        String credential = (String) result.get(1).get("credential");
        long expiry = Long.parseLong(username.substring(0, username.indexOf(':')));
        assertTrue(expiry >= Instant.now().getEpochSecond() + 299);
        assertTrue(expiry <= Instant.now().getEpochSecond() + 3601);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8))), credential);
        assertFalse(username.contains("alice"), "TURN username should not expose account username");
    }

    @Test
    void rejectsTurnUrlsWithoutStrongSharedSecret() {
        assertThrows(IllegalStateException.class,
                () -> new WebRtcIceServerService("stun:stun.example.test", "turn:turn.example.test:3478", "short", 3600));
    }
}
