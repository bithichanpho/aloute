package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordManagementServiceTest {
    private static final String RAW_TOKEN = "raw-reset-token";

    private IUserRepository users;
    private PasswordEncoder encoder;
    private RefreshTokenService refreshTokens;
    private AuthTokenService tokens;
    private PasswordManagementService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        refreshTokens = mock(RefreshTokenService.class);
        tokens = new AuthTokenService();
        service =
                new PasswordManagementService(
                        users, encoder, tokens, mock(EmailService.class), refreshTokens);
        when(encoder.encode("New-Pass1!")).thenReturn("encoded");
    }

    private User userWithResetExpiring(LocalDateTime expiry) {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setResetPasswordExpiry(expiry);
        return user;
    }

    @Test
    void validTokenIsSpentOnceAndSessionsAreRevoked() {
        String hash = tokens.hashToken(RAW_TOKEN);
        when(users.findByResetPasswordToken(hash))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));
        when(users.consumeResetToken(eq(7L), eq(hash), eq("encoded"), any(Instant.class), any()))
                .thenReturn(1);

        service.resetPassword(RAW_TOKEN, "New-Pass1!");

        ArgumentCaptor<Instant> revokedAt = ArgumentCaptor.forClass(Instant.class);
        verify(users).consumeResetToken(eq(7L), eq(hash), eq("encoded"), revokedAt.capture(), any());
        verify(refreshTokens).revokeAll(7L, revokedAt.getValue());
    }

    @Test
    void losingTheRaceAgainstAConcurrentRequestIsRejectedWithoutSideEffects() {
        String hash = tokens.hashToken(RAW_TOKEN);
        when(users.findByResetPasswordToken(hash))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));
        // Another request already spent the token between our read and our update.
        when(users.consumeResetToken(anyLong(), anyString(), anyString(), any(), any())).thenReturn(0);

        assertThrows(BadRequestException.class, () -> service.resetPassword(RAW_TOKEN, "New-Pass1!"));

        verify(refreshTokens, never()).revokeAll(anyLong(), any());
    }

    @Test
    void plaintextUuidTokensFromOlderVersionsAreNoLongerAccepted() {
        String legacy = "123e4567-e89b-12d3-a456-426614174000";
        when(users.findByResetPasswordToken(tokens.hashToken(legacy))).thenReturn(Optional.empty());
        // Even if a legacy row still held the raw value, it must not be looked up.
        when(users.findByResetPasswordToken(legacy))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));

        assertThrows(BadRequestException.class, () -> service.resetPassword(legacy, "New-Pass1!"));

        verify(users, never()).findByResetPasswordToken(legacy);
        verify(users, never()).consumeResetToken(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void expiredTokenIsRejectedBeforeAnythingIsChanged() {
        String hash = tokens.hashToken(RAW_TOKEN);
        when(users.findByResetPasswordToken(hash))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().minusMinutes(1))));

        assertThrows(BadRequestException.class, () -> service.resetPassword(RAW_TOKEN, "New-Pass1!"));

        verify(users, never()).consumeResetToken(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(BadRequestException.class, () -> service.resetPassword("  ", "New-Pass1!"));
        verify(users, never()).findByResetPasswordToken(anyString());
    }
}
