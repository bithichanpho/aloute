package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.TooManyRequestsException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.PendingUser;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Registration must answer identically whether or not the email already has an account. */
class AuthServiceRegisterTest {
    private IUserRepository users;
    private IPendingUserRepository pendings;
    private PasswordEncoder encoder;
    private EmailService email;
    private AuthRateLimitService limits;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        pendings = mock(IPendingUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        email = mock(EmailService.class);
        limits = mock(AuthRateLimitService.class);
        when(encoder.encode(anyString())).thenReturn("encoded");
        when(pendings.save(any(PendingUser.class))).thenAnswer(call -> call.getArgument(0));
        service =
                new AuthService(
                        users, pendings, mock(IRoleRepository.class), encoder, mock(JwtUtil.class),
                        email, new AuthTokenService(), mock(PasswordManagementService.class),
                        mock(FirebaseAccountProvisioningService.class), limits);
    }

    private static RegisterRequest request(String username, String mail) {
        RegisterRequest request = new RegisterRequest();
        request.setFullName(" Alice Nguyen ");
        request.setUsername(username);
        request.setEmail(mail);
        request.setPassword("Str0ng-Pass!");
        return request;
    }

    private static User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("taken@example.com");
        user.setFullName("Taken Owner");
        return user;
    }

    private static PendingUser livePending(String username, String mail, LocalDateTime lastSent) {
        PendingUser pending = new PendingUser();
        pending.setUsername(username);
        pending.setEmail(mail);
        pending.setFullName("Pending Person");
        pending.setExpiresAt(LocalDateTime.now().plusHours(5));
        pending.setVerificationEmailSentAt(lastSent);
        return pending;
    }

    @Test
    void newEmailCreatesPendingRegistrationAndSendsVerification() {
        AuthResponse response = service.register(request("Alice", "New@Example.com"));

        verify(pendings).save(any(PendingUser.class));
        verify(email).sendVerificationEmail(eq("new@example.com"), eq("Alice Nguyen"), anyString());
        assertEquals("alice", response.getUsername());
    }

    @Test
    void existingEmailGetsTheSameResponseCreatesNothingAndNotifiesTheOwner() {
        when(users.findByEmailIgnoreCase("taken@example.com")).thenReturn(Optional.of(existingUser()));

        AuthResponse existing = service.register(request("alice", "Taken@Example.com"));
        AuthResponse fresh = service.register(request("alice", "brand-new@example.com"));

        assertEquals(fresh.getUsername(), existing.getUsername());
        assertEquals(fresh.getFullName(), existing.getFullName());
        assertNull(existing.getUserId());
        assertNull(existing.getAvatarUrl());
        // Only the brand-new registration produced a pending row / verification mail.
        verify(pendings).save(any(PendingUser.class));
        verify(email).sendAccountAlreadyExistsEmail("taken@example.com", "Taken Owner");
        verify(email).sendVerificationEmail(eq("brand-new@example.com"), anyString(), anyString());
    }

    @Test
    void existingEmailStillPaysForPasswordHashing() {
        when(users.findByEmailIgnoreCase("taken@example.com")).thenReturn(Optional.of(existingUser()));

        service.register(request("alice", "taken@example.com"));

        verify(encoder).encode("Str0ng-Pass!");
    }

    @Test
    void notificationIsSkippedSilentlyWhenThrottled() {
        when(users.findByEmailIgnoreCase("taken@example.com")).thenReturn(Optional.of(existingUser()));
        doThrow(new TooManyRequestsException("slow down"))
                .when(limits)
                .checkAndRecord(anyString(), anyString(), anyInt(),
                        any(Duration.class), any(Duration.class), anyString(), anyString());

        AuthResponse response = service.register(request("alice", "taken@example.com"));

        assertEquals("alice", response.getUsername());
        verify(email, never()).sendAccountAlreadyExistsEmail(anyString(), anyString());
    }

    @Test
    void usernameOfARegisteredUserIsReportedAsTaken() {
        when(users.existsByUsernameIgnoreCase("alice")).thenReturn(true);

        BadRequestException error =
                assertThrows(BadRequestException.class, () -> service.register(request("alice", "x@example.com")));

        assertEquals("Tên đăng nhập đã được sử dụng", error.getMessage());
    }

    @Test
    void usernameHeldByAnotherEmailsPendingRegistrationIsReportedAsTaken() {
        when(pendings.findByUsernameIgnoreCase("alice"))
                .thenReturn(Optional.of(livePending("alice", "someone-else@example.com", null)));

        BadRequestException error =
                assertThrows(BadRequestException.class, () -> service.register(request("alice", "me@example.com")));

        assertEquals("Tên đăng nhập đã được sử dụng", error.getMessage());
        verify(pendings, never()).save(any());
    }

    @Test
    void livePendingEmailDoesNotRevealItselfAndIsNeverOverwritten() {
        PendingUser pending = livePending("alice", "me@example.com", LocalDateTime.now());
        when(pendings.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(pending));
        when(pendings.findByEmailIgnoreCase("me@example.com")).thenReturn(Optional.of(pending));

        AuthResponse response = service.register(request("alice", "me@example.com"));

        assertEquals("alice", response.getUsername());
        // Verification mail was sent a moment ago, so the 60s cooldown applies: nothing sent.
        verify(email, never()).sendVerificationEmail(anyString(), anyString(), anyString());
        verify(pendings, never()).save(any());
        verify(pendings, never()).delete(any());
    }

    @Test
    void livePendingEmailGetsAFreshOtpOnceTheCooldownHasPassed() {
        PendingUser pending = livePending("alice", "me@example.com", LocalDateTime.now().minusMinutes(5));
        when(pendings.findByEmailIgnoreCase("me@example.com")).thenReturn(Optional.of(pending));

        service.register(request("bob", "me@example.com"));

        verify(email).sendVerificationEmail(eq("me@example.com"), eq("Pending Person"), anyString());
        // The pending data (username, password) is untouched by the new request.
        assertEquals("alice", pending.getUsername());
    }
}
