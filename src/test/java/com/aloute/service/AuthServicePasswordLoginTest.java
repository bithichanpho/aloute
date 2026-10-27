package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Regression coverage for local password login alongside Firebase/Google login. */
class AuthServicePasswordLoginTest {
    private IUserRepository users;
    private PasswordManagementService passwordManagement;
    private JwtUtil jwt;
    private PasswordEncoder encoder;
    private AuthService auth;
    private User localUser;

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        passwordManagement = mock(PasswordManagementService.class);
        jwt = mock(JwtUtil.class);
        encoder = new BCryptPasswordEncoder();

        localUser = new User();
        localUser.setId(42L);
        localUser.setUsername("phuongtrang");
        localUser.setEmail("trang@example.com");
        localUser.setFullName("Phuong Trang");
        localUser.setRole(new Role("ROLE_USER"));
        localUser.setActive(true);
        localUser.setEmailVerified(true);
        localUser.setPassword(encoder.encode("Tr4ng-Local!Pass"));

        auth = new AuthService(
                users, mock(IPendingUserRepository.class), mock(IRoleRepository.class), encoder,
                jwt, mock(EmailService.class), new AuthTokenService(),
                passwordManagement, mock(FirebaseAccountProvisioningService.class),
                mock(AuthRateLimitService.class));
        when(jwt.generateToken("phuongtrang", 42L, "ROLE_USER")).thenReturn("test-jwt");
    }

    @Test
    void acceptsTheSamePasswordChosenAtRegistrationWhenLoggingInByUsername() {
        when(users.findByUsernameIgnoreCase("PHUONGTRANG")).thenReturn(Optional.of(localUser));
        LoginRequest request = new LoginRequest();
        request.setUsername("PHUONGTRANG");
        request.setPassword("Tr4ng-Local!Pass");

        AuthResponse response = auth.login(request);

        assertEquals("phuongtrang", response.getUsername());
        assertEquals("test-jwt", response.getAccessToken());
    }

    @Test
    void acceptsTheAtPrefixedUsernameUsedInProfileHandles() {
        when(users.findByUsernameIgnoreCase("phuongtrang")).thenReturn(Optional.of(localUser));
        LoginRequest request = new LoginRequest();
        request.setUsername("@phuongtrang");
        request.setPassword("Tr4ng-Local!Pass");

        AuthResponse response = auth.login(request);

        assertEquals("phuongtrang", response.getUsername());
    }

    @Test
    void acceptsLoginByEmailToo() {
        when(users.findByUsernameIgnoreCase("trang@example.com")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("trang@example.com")).thenReturn(Optional.of(localUser));
        LoginRequest request = new LoginRequest();
        request.setUsername("trang@example.com");
        request.setPassword("Tr4ng-Local!Pass");

        AuthResponse response = auth.login(request);

        assertEquals("phuongtrang", response.getUsername());
    }

    @Test
    void rejectsAnIncorrectLocalPassword() {
        when(users.findByUsernameIgnoreCase("phuongtrang")).thenReturn(Optional.of(localUser));
        LoginRequest request = new LoginRequest();
        request.setUsername("phuongtrang");
        request.setPassword("Wrong-Pass1!");

        assertThrows(UnauthorizedException.class, () -> auth.login(request));
    }

    @Test
    void changingPasswordRequiresCurrentPasswordAndConfirmation() {
        auth.changePassword(42L, "Old-Local1!Pass", "New-Local1!Pass", "New-Local1!Pass");
        verify(passwordManagement).changePassword(42L, "Old-Local1!Pass", "New-Local1!Pass", "New-Local1!Pass");
    }
}
