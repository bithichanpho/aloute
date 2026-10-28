package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.NotificationAccountCleanup;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountSecurityServiceTest {
    @Test
    void currentPasswordCanAuthorizeSensitiveAccountAction() {
        IUserRepository users = mock(IUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        User user = new User();
        user.setId(1L);
        user.setEmail("alice@example.com");
        user.setPassword("encoded-password");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(passwords.matches("Current1!Pass", "encoded-password")).thenReturn(true);
        AccountSecurityService service = service(users, passwords);

        assertDoesNotThrow(() -> service.checkPassword(1L, "Current1!Pass"));
    }

    @Test
    void incorrectCurrentPasswordIsRejected() {
        IUserRepository users = mock(IUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        User user = new User();
        user.setId(1L);
        user.setEmail("alice@example.com");
        user.setPassword("encoded-password");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(passwords.matches("wrong", "encoded-password")).thenReturn(false);
        AccountSecurityService service = service(users, passwords);

        assertThrows(UnauthorizedException.class, () -> service.checkPassword(1L, "wrong"));
    }


    @Test
    void deletionRejectsNonMatchingPasswordConfirmationBeforeMutation() {
        AccountSecurityService service = service(mock(IUserRepository.class), mock(PasswordEncoder.class));

        assertThrows(BadRequestException.class,
                () -> service.deleteAccount(1L, "Current1!Pass", "different"));
    }

    private AccountSecurityService service(IUserRepository users, PasswordEncoder passwords) {
        return new AccountSecurityService(
                users, passwords, mock(AuthRateLimitService.class),
                mock(IFriendRepository.class), mock(ICloseFriendRepository.class),
                mock(ICommunityMemberRepository.class), mock(IGroupMemberRepository.class),
                mock(NotificationAccountCleanup.class), mock(IGroupReadStatusRepository.class),
                mock(RefreshTokenService.class), mock(AccountDataCleanupService.class),
                mock(AuthenticationUserCache.class));
    }
}
