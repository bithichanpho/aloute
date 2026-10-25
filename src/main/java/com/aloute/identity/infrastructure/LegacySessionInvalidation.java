package com.aloute.identity.infrastructure;

import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.identity.api.SessionInvalidation;
import com.aloute.service.AuthenticationUserCache;
import com.aloute.service.RefreshTokenService;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Transitional identity adapter; the existing revocation watermark and cache policy stay intact. */
@Service
public class LegacySessionInvalidation implements SessionInvalidation {
    private final RefreshTokenService tokens;
    private final AuthenticationUserCache cache;

    public LegacySessionInvalidation(RefreshTokenService tokens, AuthenticationUserCache cache) {
        this.tokens = tokens;
        this.cache = cache;
    }

    public void revokeAll(Long userId, Instant revokedAt, String username) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(revokedAt, "revokedAt");
        // Null adapters are supported only by legacy direct-construction callers/tests.
        if (tokens != null) tokens.revokeAll(userId, revokedAt);
        if (cache != null) TransactionCommitHooks.afterCommit(() -> cache.invalidate(username));
    }
}
