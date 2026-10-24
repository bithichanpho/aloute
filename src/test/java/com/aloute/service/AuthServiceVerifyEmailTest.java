package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.config.JwtUtil;
import com.aloute.model.entity.PendingUser;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceVerifyEmailTest {
    private static final String EMAIL = "me@example.com";
    private static final String CODE = "123456";

    private IUserRepository users;
    private IPendingUserRepository pendings;
    private IRoleRepository roles;
    private AuthTokenService tokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        pendings = mock(IPendingUserRepository.class);
        roles = mock(IRoleRepository.class);
        tokens = new AuthTokenService();
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("encoded");
        when(roles.findByName("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        service = new AuthService(
                users, pendings, roles, encoder, mock(JwtUtil.class), mock(EmailService.class),
                tokens, mock(AccountSecurityService.class), mock(PasswordManagementService.class),
                mock(FirebaseAccountProvisioningService.class), mock(AuthRateLimitService.class));
    }

    private PendingUser pending(LocalDateTime expiry) {
        PendingUser pending = new PendingUser();
        pending.setUsername("alice");
        pending.setEmail(EMAIL);
        pending.setFullName("Alice Nguyen");
        pending.setPassword("encoded-password");
        pending.setVerificationToken(tokens.hashOtp(EMAIL, CODE));
        pending.setVerificationTokenExpiry(expiry);
        pending.setExpiresAt(LocalDateTime.now().plusHours(5));
        return pending;
    }

    @Test
    void correctOtpCreatesVerifiedAccountAndRemovesPendingRow() {
        PendingUser pending = pending(LocalDateTime.now().plusMinutes(5));
        when(pendings.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(pending));

        service.verifyEmail(" Me@Example.com ", CODE);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals("alice", saved.getValue().getUsername());
        assertTrue(saved.getValue().isEmailVerified());
        verify(pendings).delete(pending);
    }

    @Test
    void wrongOrExpiredOtpNeverCreatesAccount() {
        when(pendings.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(pending(LocalDateTime.now().plusMinutes(5))));
        assertThrows(BadRequestException.class, () -> service.verifyEmail(EMAIL, "654321"));

        when(pendings.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(pending(LocalDateTime.now().minusMinutes(1))));
        assertThrows(BadRequestException.class, () -> service.verifyEmail(EMAIL, CODE));

        verify(users, never()).save(any());
        verify(pendings, never()).delete(any());
    }

    @Test
    void malformedOtpIsRejectedBeforeDatabaseLookup() {
        assertThrows(BadRequestException.class, () -> service.verifyEmail(EMAIL, "12ab"));
        verify(pendings, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void malformedAndWrongAttemptsDoNotInvalidateTheCorrectCode() {
        PendingUser pending=pending(LocalDateTime.now().plusMinutes(5));
        String hash=pending.getVerificationToken();
        LocalDateTime expiry=pending.getVerificationTokenExpiry();
        when(pendings.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(pending));

        assertThrows(BadRequestException.class,()->service.verifyEmail(EMAIL,"12ab"));
        assertThrows(BadRequestException.class,()->service.verifyEmail(EMAIL,"000000"));
        assertEquals(hash,pending.getVerificationToken());
        assertEquals(expiry,pending.getVerificationTokenExpiry());
        verify(users,never()).save(any());verify(pendings,never()).delete(any());

        service.verifyEmail(EMAIL,CODE);
        verify(users).save(any());verify(pendings).delete(pending);
    }
}
