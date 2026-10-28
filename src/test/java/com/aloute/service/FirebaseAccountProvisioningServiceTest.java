package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class FirebaseAccountProvisioningServiceTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final IPendingUserRepository pendingUsers = mock(IPendingUserRepository.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private FirebaseAccountProvisioningService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new FirebaseAccountProvisioningService(users, pendingUsers, jwt);
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
    void googleLoginWorksWithoutASecondFactorSetup() {
        var auth = service.loginWithFirebaseProfile("a@x.com", true, "Alice", null);
        assertEquals("jwt", auth.getAccessToken());
    }

    @Test
    void unregisteredGoogleEmailMustRegisterInsteadOfCreatingAnAccountDuringLogin() {
        when(users.findByEmailIgnoreCase("new.person@gmail.com")).thenReturn(Optional.empty());
        when(pendingUsers.existsByEmailIgnoreCase("new.person@gmail.com")).thenReturn(false);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.loginWithFirebaseProfile(" New.Person@gmail.com ", true, "New Person", null));

        assertEquals(FirebaseAccountProvisioningService.EMAIL_NOT_REGISTERED_MESSAGE, exception.getMessage());
        verify(users, never()).save(any(User.class));
        verify(jwt, never()).generateToken(any(), any(), any());
    }

    @Test
    void pendingRegistrationMustBeVerifiedBeforeGoogleLogin() {
        when(users.findByEmailIgnoreCase("pending@gmail.com")).thenReturn(Optional.empty());
        when(pendingUsers.existsByEmailIgnoreCase("pending@gmail.com")).thenReturn(true);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.loginWithFirebaseProfile("pending@gmail.com", true, "Pending", null));

        assertEquals(FirebaseAccountProvisioningService.EMAIL_PENDING_VERIFICATION_MESSAGE, exception.getMessage());
        verify(users, never()).save(any(User.class));
        verify(jwt, never()).generateToken(any(), any(), any());
    }
}
