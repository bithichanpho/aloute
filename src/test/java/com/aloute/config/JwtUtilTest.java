package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.aloute.service.AccessTokenRevocationService;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilTest {
    private static final String KEY = "test-jwt-signing-key-random-value-longer-than-32-bytes";

    @Test
    void generatesValidShortLivedAccessTokensUsingUtf8SigningKey() {
        JwtUtil jwt = new JwtUtil(KEY, 900_000L, mock(AccessTokenRevocationService.class));
        String token = jwt.generateToken("alice", 10L, "ROLE_USER");
        assertTrue(jwt.isTokenValid(token, "alice"));
        assertEquals("alice", jwt.extractUsername(token));
        assertEquals(10L, jwt.extractUserId(token));
        assertEquals(900_000L, jwt.getExpirationMs());
    }

    @Test
    void rejectsWeakJwtSecretAndTwentyFourHourPlusExpiry() {
        AccessTokenRevocationService revocations = mock(AccessTokenRevocationService.class);
        assertThrows(IllegalStateException.class, () -> new JwtUtil("short", 900_000L, revocations));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(KEY, 86_400_001L, revocations));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(KEY, 59_999L, revocations));
    }

    @Test
    void rejectsAnAccessTokenThatWasRevoked() {
        AccessTokenRevocationService revocations = mock(AccessTokenRevocationService.class);
        JwtUtil jwt = new JwtUtil(KEY, 900_000L, revocations);
        String token = jwt.generateToken("alice", 10L, "ROLE_USER");

        when(revocations.isRevoked(token)).thenReturn(true);

        assertFalse(jwt.isTokenValid(token, "alice"));
    }
}
