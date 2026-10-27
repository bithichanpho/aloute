package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aloute.common.exception.TwoFactorRequiredException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.LoginRequest;
import com.aloute.model.entity.User;
import com.aloute.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthMergeCompatibilityTest {
    @Test
    void atUsernameNormalizationDoesNotBypassExistingTwoFactorPolicy() {
        var users = mock(IUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var security = mock(AccountSecurityService.class);
        var jwt = mock(JwtUtil.class);
        var service = new AuthService(users, mock(IPendingUserRepository.class), mock(IRoleRepository.class),
                encoder, jwt, mock(EmailService.class), new AuthTokenService(), security,
                mock(PasswordManagementService.class), mock(FirebaseAccountProvisioningService.class), mock(AuthRateLimitService.class));
        var user = new User(); user.setId(1L); user.setUsername("alice"); user.setPassword("stored-hash");
        user.setEmailVerified(true);
        when(users.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(encoder.matches("test-password", "stored-hash")).thenReturn(true);
        doThrow(new TwoFactorRequiredException()).when(security).verifyLogin(1L, null);
        var request = new LoginRequest(); request.setUsername(" @alice "); request.setPassword("test-password");
        assertThrows(TwoFactorRequiredException.class, () -> service.login(request));
        verify(users).findByUsernameIgnoreCase("alice");
        verify(security).verifyLogin(1L, null);
        verifyNoInteractions(jwt);
    }
}
