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
    private static final String EMAIL = "alice@example.com";
    private static final String OTP = "123456";

    private IUserRepository users;
    private PasswordEncoder encoder;
    private RefreshTokenService refreshTokens;
    private AuthTokenService tokens;
    private EmailService emailService;
    private PasswordManagementService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        refreshTokens = mock(RefreshTokenService.class);
        tokens = new AuthTokenService();
        emailService = mock(EmailService.class);
        service = new PasswordManagementService(
                users, encoder, tokens, emailService, refreshTokens);
        when(encoder.encode("New-Pass1!")).thenReturn("encoded-password");
    }

    private User userWithResetExpiring(LocalDateTime expiry) {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setEmail(EMAIL);
        user.setResetPasswordToken(tokens.hashOtp(EMAIL, OTP));
        user.setResetPasswordExpiry(expiry);
        return user;
    }

    @Test
    void validOtpIsSpentOnceAndSessionsAreRevoked() {
        String otpHash = tokens.hashOtp(EMAIL, OTP);
        when(users.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));
        when(users.consumeResetToken(eq(7L), eq(otpHash), eq("encoded-password"), any(Instant.class), any()))
                .thenReturn(1);

        service.resetPassword(EMAIL, OTP, "New-Pass1!");

        ArgumentCaptor<Instant> revokedAt = ArgumentCaptor.forClass(Instant.class);
        verify(users).consumeResetToken(eq(7L), eq(otpHash), eq("encoded-password"), revokedAt.capture(), any());
        verify(refreshTokens).revokeAll(7L, revokedAt.getValue());
    }

    @Test
    void losingTheRaceAgainstAConcurrentRequestIsRejectedWithoutSideEffects() {
        when(users.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));
        when(users.consumeResetToken(anyLong(), anyString(), anyString(), any(), any())).thenReturn(0);

        assertThrows(BadRequestException.class,
                () -> service.resetPassword(EMAIL, OTP, "New-Pass1!"));

        verify(refreshTokens, never()).revokeAll(anyLong(), any());
    }

    @Test
    void wrongOtpIsRejectedBeforePasswordIsChanged() {
        when(users.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().plusMinutes(5))));
        assertThrows(BadRequestException.class,
                () -> service.resetPassword(EMAIL, "654321", "New-Pass1!"));

        verify(users, never()).consumeResetToken(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void expiredOtpIsRejectedBeforeAnythingIsChanged() {
        when(users.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(userWithResetExpiring(LocalDateTime.now().minusMinutes(1))));
        assertThrows(BadRequestException.class,
                () -> service.resetPassword(EMAIL, OTP, "New-Pass1!"));

        verify(users, never()).consumeResetToken(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void malformedOtpIsRejectedWithoutDatabaseLookup() {
        assertThrows(BadRequestException.class,
                () -> service.resetPassword(EMAIL, "12ab", "New-Pass1!"));
        verify(users, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void forgotPasswordSendsSixDigitOtpAndStoresOnlyItsHash() {
        User user = new User();
        user.setEmail(EMAIL);
        user.setFullName("Alice");
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        service.forgotPassword(" Alice@Example.com ");

        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendResetPasswordEmail(eq(EMAIL), eq("Alice"), otp.capture());
        org.junit.jupiter.api.Assertions.assertTrue(otp.getValue().matches("\\d{6}"));
        org.junit.jupiter.api.Assertions.assertEquals(
                tokens.hashOtp(EMAIL, otp.getValue()), user.getResetPasswordToken());
        verify(users).save(user);
    }
}
