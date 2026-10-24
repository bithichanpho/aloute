package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.TwoFactorRequiredException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

class FirebaseAccountProvisioningServiceTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final AccountSecurityService security = mock(AccountSecurityService.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private FirebaseAccountProvisioningService service;
    private User user;

    @BeforeEach
    void setUp() {
        service =
                new FirebaseAccountProvisioningService(
                        users,
                        mock(IPendingUserRepository.class),
                        mock(IRoleRepository.class),
                        mock(PasswordEncoder.class),
                        jwt,
                        security);
        user = new User();
        user.setId(5L);
        user.setUsername("alice");
        user.setEmail("a@x.com");
        user.setActive(true);
        user.setRole(new Role("ROLE_USER"));
        when(users.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.generateToken(any(), any(), any())).thenReturn("jwt");
    }

    @Test
    void googleLoginRequiresTwoFactorWhenEnabledAndNoCodeGiven() {
        doThrow(new TwoFactorRequiredException()).when(security).verifyLogin(5L, null);

        assertThrows(
                TwoFactorRequiredException.class,
                () -> service.loginWithFirebaseProfile("a@x.com", true, "Alice", null, null));

        verify(jwt, never()).generateToken(any(), any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void googleLoginRejectsWrongTwoFactorCode() {
        doThrow(new UnauthorizedException("bad")).when(security).verifyLogin(5L, "000000");

        assertThrows(
                UnauthorizedException.class,
                () -> service.loginWithFirebaseProfile("a@x.com", true, "Alice", null, "000000"));

        verify(jwt, never()).generateToken(any(), any(), any());
    }

    @Test
    void googleLoginSucceedsWithValidCodeOrWhenTwoFactorDisabled() {
        var auth = service.loginWithFirebaseProfile("a@x.com", true, "Alice", null, "123456");

        verify(security).verifyLogin(5L, "123456");
        assertEquals("jwt", auth.getAccessToken());
    }

    @Test
    void googleSignupNeverReceivesDeletedAccountUsername() {
        IRoleRepository roles = mock(IRoleRepository.class);
        when(roles.findByName("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        FirebaseAccountProvisioningService signup =
                new FirebaseAccountProvisioningService(
                        users,
                        mock(IPendingUserRepository.class),
                        roles,
                        mock(PasswordEncoder.class),
                        jwt,
                        security);

        signup.loginWithFirebaseProfile("Deleted_123@gmail.com", true, "Mallory", null, null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertFalse(
                com.aloute.common.util.UsernamePolicy.isReserved(saved.getValue().getUsername()),
                "generated username must not be in the deleted-account namespace");
        assertEquals("user_deleted_123", saved.getValue().getUsername());
    }
}
