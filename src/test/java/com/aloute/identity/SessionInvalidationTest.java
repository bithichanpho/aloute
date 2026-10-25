package com.aloute.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.identity.infrastructure.LegacySessionInvalidation;
import com.aloute.service.AuthenticationUserCache;
import com.aloute.service.RefreshTokenService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class SessionInvalidationTest {
    private final RefreshTokenService tokens = mock(RefreshTokenService.class);
    private final AuthenticationUserCache cache = mock(AuthenticationUserCache.class);
    private final LegacySessionInvalidation service = new LegacySessionInvalidation(tokens, cache);

    @Test
    void revocationUsesTheOwnersTimestampAndInvalidatesCacheAfterCommit() {
        Instant time = Instant.parse("2026-10-24T00:00:00Z");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.revokeAll(7L, time, "alice");
            verify(tokens).revokeAll(7L, time);
            verifyNoInteractions(cache);
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(cache).invalidate("alice");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedRevocationCannotInvalidateAnUncommittedAuthenticationSnapshot() {
        Instant time = Instant.EPOCH;
        doThrow(new IllegalStateException("storage unavailable")).when(tokens).revokeAll(7L, time);
        assertThrows(IllegalStateException.class, () -> service.revokeAll(7L, time, "alice"));
        verifyNoInteractions(cache);
    }
}
