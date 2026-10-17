package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JwtUtilTest {
    private static final String KEY = "test-jwt-signing-key-random-value-longer-than-32-bytes";

    @Test
    void generatesValidShortLivedAccessTokensUsingUtf8SigningKey() {
        JwtUtil jwt = new JwtUtil(KEY, 900_000L);
        String token = jwt.generateToken("alice", 10L, "ROLE_USER");
        assertTrue(jwt.isTokenValid(token, "alice"));
        assertEquals("alice", jwt.extractUsername(token));
        assertEquals(10L, jwt.extractUserId(token));
        assertEquals(900_000L, jwt.getExpirationMs());
    }

    @Test
    void rejectsWeakJwtSecretAndTwentyFourHourPlusExpiry() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("short", 900_000L));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(KEY, 86_400_001L));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(KEY, 59_999L));
    }
}
