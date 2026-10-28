package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordManagementGoogleLinkTest {
    @Test
    void aGoogleOnlyAccountWithUnknownLocalPasswordMustUsePasswordResetFirst() {
        IUserRepository users = mock(IUserRepository.class);
        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User();
        user.setId(15L);
        user.setUsername("caso");
        user.setEmail("caso@example.com");
        user.setPassword(encoder.encode("unknowable-generated-old-password"));
        when(users.findById(15L)).thenReturn(Optional.of(user));

        PasswordManagementService passwords = new PasswordManagementService(
                users, encoder, new AuthTokenService(), mock(EmailService.class), refreshTokens);

        assertThrows(BadRequestException.class,
                () -> passwords.changePassword(15L, "unknown-password", "New-Local1!Pass", "New-Local1!Pass"));
        verify(users, never()).save(any());
        verify(refreshTokens, never()).revokeAll(any(Long.class), any(java.time.Instant.class));
    }
}
