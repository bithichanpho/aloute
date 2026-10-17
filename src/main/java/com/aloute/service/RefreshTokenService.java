package com.aloute.service;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.model.entity.RefreshToken;
import com.aloute.model.entity.User;
import com.aloute.repository.IRefreshTokenRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.common.util.TransactionCommitHooks;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
    public static final String COOKIE_NAME = "ALOUTE_REFRESH";
    private final IRefreshTokenRepository tokens;
    private final IUserRepository users;
    private final AuthTokenService tokenGenerator;
    private final int lifetimeDays;
    private final long replayGraceSeconds;
    private final AuthenticationUserCache authenticationUserCache;

    @Autowired
    public RefreshTokenService(
            IRefreshTokenRepository tokens,
            IUserRepository users,
            AuthTokenService tokenGenerator,
            @Value("${app.security.refresh-token-days:30}") int lifetimeDays,
            @Value("${app.security.refresh-token-replay-grace-seconds:20}") long replayGraceSeconds,
            AuthenticationUserCache authenticationUserCache) {
        this.tokens = tokens;
        this.users = users;
        this.tokenGenerator = tokenGenerator;
        this.lifetimeDays = Math.max(1, Math.min(90, lifetimeDays));
        this.replayGraceSeconds = Math.max(10L, Math.min(30L, replayGraceSeconds));
        this.authenticationUserCache = authenticationUserCache;
    }

    /** Compatibility constructor for tests that do not need replay/cache customization. */
    public RefreshTokenService(
            IRefreshTokenRepository tokens, IUserRepository users,
            AuthTokenService tokenGenerator, int lifetimeDays) {
        this(tokens, users, tokenGenerator, lifetimeDays, 20L, null);
    }

    /** Compatibility constructor retained for cache invalidation tests. */
    public RefreshTokenService(
            IRefreshTokenRepository tokens, IUserRepository users,
            AuthTokenService tokenGenerator, int lifetimeDays,
            AuthenticationUserCache authenticationUserCache) {
        this(tokens, users, tokenGenerator, lifetimeDays, 20L, authenticationUserCache);
    }

    @Transactional
    public String issue(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        String raw = tokenGenerator.generateToken();
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(tokenGenerator.hashToken(raw));
        token.setExpiresAt(Instant.now().plus(lifetimeDays, ChronoUnit.DAYS));
        tokens.save(token);
        return raw;
    }

    /** Rotates an opaque token once; duplicate requests inside the grace window do not revoke other sessions. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new UnauthorizedException("Phiên đăng nhập đã hết hạn");
        RefreshToken current = tokens.findByTokenHash(tokenGenerator.hashToken(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập đã hết hạn"));
        Instant now = Instant.now();
        if (current.getRevokedAt() != null) {
            // Another tab may have rotated the cookie moments ago. Reject the stale request,
            // but do not treat a near-simultaneous retry as theft or sign out other devices.
            if (isWithinReplayGrace(current, now)) {
                throw new UnauthorizedException(
                        "Phiên đăng nhập vừa được làm mới ở tab khác; vui lòng thử lại");
            }
            // Reuse outside the short grace window is treated as a possible stolen-token replay.
            revokeAll(current.getUserId(), now);
            throw new UnauthorizedException("Phiên đăng nhập đã được sử dụng lại; vui lòng đăng nhập lại");
        }
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            // Expiration alone must not revoke unrelated active sessions on other devices.
            current.setRevokedAt(now);
            tokens.save(current);
            throw new UnauthorizedException("Phiên đăng nhập đã hết hạn");
        }
        if (tokens.revokeIfActive(current.getId(), now) != 1) {
            // The compare-and-set can lose a race with another tab. Reload the row after the
            // modifying query clears the persistence context to distinguish that race from replay.
            RefreshToken latest = tokens.findByTokenHash(current.getTokenHash()).orElse(current);
            if (isWithinReplayGrace(latest, Instant.now())) {
                throw new UnauthorizedException(
                        "Phiên đăng nhập vừa được làm mới ở tab khác; vui lòng thử lại");
            }
            revokeAll(current.getUserId(), now);
            throw new UnauthorizedException("Phiên đăng nhập đã được sử dụng lại; vui lòng đăng nhập lại");
        }
        User user = users.findById(current.getUserId())
                .filter(User::isActive)
                .orElseThrow(() -> new UnauthorizedException("Tài khoản không còn hoạt động"));
        if (user.getTokensRevokedAt() != null
                && (current.getCreatedAt() == null || !current.getCreatedAt().isAfter(user.getTokensRevokedAt()))) {
            throw new UnauthorizedException("Phiên đăng nhập đã bị thu hồi");
        }
        String rotated = issue(user.getId());
        return new Rotation(user, rotated);
    }

    private boolean isWithinReplayGrace(RefreshToken token, Instant now) {
        Instant revokedAt = token == null ? null : token.getRevokedAt();
        return revokedAt != null
                && !revokedAt.isAfter(now)
                && !revokedAt.isBefore(now.minusSeconds(replayGraceSeconds));
    }

    public long getLifetimeSeconds() {
        return java.time.Duration.ofDays(lifetimeDays).toSeconds();
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.findByTokenHash(tokenGenerator.hashToken(rawToken)).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            tokens.save(token);
            scheduleUserCacheInvalidation(token.getUserId());
        });
    }

    @Transactional
    public void revokeAll(Long userId, Instant at) {
        if (userId == null) return;
        Instant revokedAt = at == null ? Instant.now() : at;
        List<RefreshToken> active = tokens.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(token -> token.setRevokedAt(revokedAt));
        if (!active.isEmpty()) tokens.saveAll(active);

        // Refresh-token revocation alone would still leave stateless access JWTs valid. Advance
        // the account revocation watermark as part of the same PostgreSQL transaction so JwtAuthFilter
        // rejects JWTs issued before this moment as well.
        String username = users.findById(userId).map(user -> {
            if (user.getTokensRevokedAt() == null || user.getTokensRevokedAt().isBefore(revokedAt)) {
                user.setTokensRevokedAt(revokedAt);
                users.save(user);
            }
            return user.getUsername();
        }).orElse(null);
        if (authenticationUserCache != null && username != null && !username.isBlank()) {
            // Evict only after commit; other users' cached snapshots remain warm.
            TransactionCommitHooks.afterCommit(() -> authenticationUserCache.invalidate(username));
        }
    }

    private void scheduleUserCacheInvalidation(Long userId) {
        if (authenticationUserCache == null || userId == null) return;
        String username = users.findById(userId).map(User::getUsername).orElse(null);
        if (username != null && !username.isBlank()) {
            TransactionCommitHooks.afterCommit(() -> authenticationUserCache.invalidate(username));
        }
    }

    public record Rotation(User user, String refreshToken) {}
}
