package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.model.entity.RefreshToken;
import com.aloute.model.entity.User;
import com.aloute.repository.IRefreshTokenRepository;
import com.aloute.repository.IUserRepository;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {
    @Test
    void rotatesRefreshTokenUsingAtomicCompareAndSet() {
        IRefreshTokenRepository tokens = mock(IRefreshTokenRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        AuthTokenService hashes = new AuthTokenService();
        RefreshTokenService service = new RefreshTokenService(tokens, users, hashes, 30);

        String raw = "refresh-token-original";
        RefreshToken stored = new RefreshToken();
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "id", 99L);
        stored.setUserId(42L);
        stored.setTokenHash(hashes.hashToken(raw));
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokens.findByTokenHash(hashes.hashToken(raw))).thenReturn(Optional.of(stored));
        when(tokens.revokeIfActive(eq(99L), any(Instant.class))).thenReturn(1);
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        user.setActive(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));

        RefreshTokenService.Rotation rotation = service.rotate(raw);

        assertNotNull(rotation.refreshToken());
        assertNotEquals(raw, rotation.refreshToken());
        verify(tokens).revokeIfActive(eq(99L), any(Instant.class));
    }

    @Test
    void expiredRefreshTokenDoesNotRevokeOtherActiveSessions() {
        IRefreshTokenRepository tokens = mock(IRefreshTokenRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        AuthTokenService hashes = new AuthTokenService();
        RefreshTokenService service = new RefreshTokenService(tokens, users, hashes, 30);
        String raw = "refresh-token-expired";
        RefreshToken expired = new RefreshToken();
        expired.setUserId(42L);
        expired.setTokenHash(hashes.hashToken(raw));
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(tokens.findByTokenHash(hashes.hashToken(raw))).thenReturn(Optional.of(expired));

        assertThrows(UnauthorizedException.class, () -> service.rotate(raw));
        verify(tokens).save(expired);
        org.mockito.Mockito.verify(tokens, org.mockito.Mockito.never())
                .findByUserIdAndRevokedAtIsNull(42L);
    }

    @Test
    void revocationInvalidatesOnlyTheAffectedUserCacheEntry() {
        IRefreshTokenRepository tokens = mock(IRefreshTokenRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        AuthTokenService hashes = new AuthTokenService();
        User alice = new User(); alice.setId(42L); alice.setUsername("alice");
        User bob = new User(); bob.setId(84L); bob.setUsername("bob");
        when(users.findById(42L)).thenReturn(Optional.of(alice));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(tokens.findByUserIdAndRevokedAtIsNull(42L)).thenReturn(List.of());
        AuthenticationUserCache cache = new AuthenticationUserCache(users, Duration.ofSeconds(30));
        cache.findByUsername("alice");
        cache.findByUsername("bob");

        RefreshTokenService service = new RefreshTokenService(tokens, users, hashes, 30, cache);
        service.revokeAll(42L, Instant.now());
        cache.findByUsername("alice");
        cache.findByUsername("bob");

        verify(users, times(2)).findByUsername("alice");
        verify(users, times(1)).findByUsername("bob");
    }

    @Test
    void replayedRefreshTokenOutsideGraceWindowRevokesRemainingActiveTokensAndIsRejected() {
        IRefreshTokenRepository tokens = mock(IRefreshTokenRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        AuthTokenService hashes = new AuthTokenService();
        RefreshTokenService service = new RefreshTokenService(tokens, users, hashes, 30);
        String raw = "refresh-token-replayed";
        RefreshToken replayed = new RefreshToken();
        replayed.setUserId(42L);
        replayed.setTokenHash(hashes.hashToken(raw));
        replayed.setExpiresAt(Instant.now().plusSeconds(3600));
        replayed.setRevokedAt(Instant.now().minusSeconds(60));
        RefreshToken anotherSession = new RefreshToken();
        anotherSession.setUserId(42L);
        anotherSession.setTokenHash(hashes.hashToken("other"));
        anotherSession.setExpiresAt(Instant.now().plusSeconds(3600));
        when(tokens.findByTokenHash(hashes.hashToken(raw))).thenReturn(Optional.of(replayed));
        when(tokens.findByUserIdAndRevokedAtIsNull(42L)).thenReturn(List.of(anotherSession));
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        when(users.findById(42L)).thenReturn(Optional.of(user));

        assertThrows(UnauthorizedException.class, () -> service.rotate(raw));
        verify(tokens).saveAll(List.of(anotherSession));
        org.junit.jupiter.api.Assertions.assertNotNull(user.getTokensRevokedAt());
        verify(users).save(user);
    }
    @Test
    void refreshTokenReusedInsideGraceWindowDoesNotRevokeOtherSessions() {
        IRefreshTokenRepository tokens = mock(IRefreshTokenRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        AuthTokenService hashes = new AuthTokenService();
        RefreshTokenService service = new RefreshTokenService(tokens, users, hashes, 30);
        String raw = "refresh-token-replayed-within-grace";
        RefreshToken replayed = new RefreshToken();
        replayed.setUserId(42L);
        replayed.setTokenHash(hashes.hashToken(raw));
        replayed.setExpiresAt(Instant.now().plusSeconds(3600));
        replayed.setRevokedAt(Instant.now().minusSeconds(2));
        when(tokens.findByTokenHash(hashes.hashToken(raw))).thenReturn(Optional.of(replayed));

        assertThrows(UnauthorizedException.class, () -> service.rotate(raw));
        org.mockito.Mockito.verify(tokens, org.mockito.Mockito.never())
                .findByUserIdAndRevokedAtIsNull(42L);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).save(any(User.class));
    }

}
