package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.model.document.AccountSecurity;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.mongodb.client.result.UpdateResult;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

class AccountSecurityServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final TotpService totp = new TotpService();
    private final SecretEncryptionService encryption = new SecretEncryptionService("unit-test-aes-encryption-key-at-least-32-bytes", "");
    private final AuthRateLimitService limits = mock(AuthRateLimitService.class);
    private final AccountSecurityService service =
            new AccountSecurityService(
                    mongo,
                    users,
                    passwords,
                    totp,
                    encryption,
                    new AuthTokenService(),
                    limits,
                    mock(com.aloute.repository.IFriendRepository.class),
                    mock(com.aloute.repository.ICloseFriendRepository.class),
                    mock(com.aloute.repository.ICommunityMemberRepository.class),
                    mock(com.aloute.repository.IGroupMemberRepository.class),
                    mock(com.aloute.repository.INotificationRepository.class),
                    mock(com.aloute.repository.IUserPushTokenRepository.class),
                    mock(com.aloute.repository.IGroupReadStatusRepository.class),
                    mock(RefreshTokenService.class),
                    mock(AccountDataCleanupService.class));

    @Test
    void usedTotpCannotBeReplayed() {
        String secret = totp.newSecret();
        AccountSecurity security = new AccountSecurity();
        security.setId(1L);
        security.setEnabled(true);
        security.setSecret(encryption.encrypt(secret));
        when(mongo.findById(1L, AccountSecurity.class)).thenReturn(security);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(AccountSecurity.class)))
                .thenReturn(
                        UpdateResult.acknowledged(1, 1L, null),
                        UpdateResult.acknowledged(0, 0L, null));
        String code = totp.code(secret, Instant.now().getEpochSecond() / 30);
        assertDoesNotThrow(() -> service.verifyLogin(1L, code));
        assertThrows(UnauthorizedException.class, () -> service.verifyLogin(1L, code));
    }

    @Test
    void passwordAloneCannotReplaceEnabledTwoFactorSecret() {
        validPassword();
        AccountSecurity security = new AccountSecurity();
        security.setEnabled(true);
        when(mongo.findById(1L, AccountSecurity.class)).thenReturn(security);
        assertThrows(BadRequestException.class, () -> service.setup(1L, "password"));
        verify(mongo, never())
                .updateFirst(any(Query.class), any(Update.class), eq(AccountSecurity.class));
    }

    @Test
    void staleEnrollmentCannotOverwriteNewerSettings() {
        validPassword();
        String secret = totp.newSecret();
        AccountSecurity security = new AccountSecurity();
        security.setId(1L);
        security.setPendingSecret(encryption.encrypt(secret));
        security.setPendingExpiresAt(Instant.now().plusSeconds(600));
        when(mongo.findById(1L, AccountSecurity.class)).thenReturn(security);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(AccountSecurity.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        String code = totp.code(secret, Instant.now().getEpochSecond() / 30);
        assertThrows(BadRequestException.class, () -> service.enable(1L, "password", code));
        verify(mongo, never()).save(any(AccountSecurity.class));
    }

    @Test
    void successfulTotpGivesTheFailureBudgetBack() {
        String code = enabledTwoFactorWithUpdateResults(UpdateResult.acknowledged(1, 1L, null));
        assertDoesNotThrow(() -> service.verifyLogin(1L, code));
        verify(limits).reset("TOTP", "1");
    }

    @Test
    void failedTotpKeepsConsumingTheFailureBudget() {
        enabledTwoFactorWithUpdateResults(UpdateResult.acknowledged(0, 0L, null));
        assertThrows(UnauthorizedException.class, () -> service.verifyLogin(1L, "000000"));
        verify(limits, never()).reset(anyString(), anyString());
    }

    @Test
    void validRecoveryCodeAlsoGivesTheFailureBudgetBack() {
        enabledTwoFactorWithUpdateResults(UpdateResult.acknowledged(1, 1L, null));
        assertDoesNotThrow(() -> service.verifyLogin(1L, "recovery-code"));
        verify(limits).reset("TOTP", "1");
    }

    @Test
    void limiterOutageAfterAValidCodeDoesNotFailTheLogin() {
        String code = enabledTwoFactorWithUpdateResults(UpdateResult.acknowledged(1, 1L, null));
        doThrow(new IllegalStateException("redis down")).when(limits).reset(anyString(), anyString());
        assertDoesNotThrow(() -> service.verifyLogin(1L, code));
    }

    @Test
    void twoFactorAttemptsAreLimitedToFivePerFifteenMinutes() {
        String code = enabledTwoFactorWithUpdateResults(UpdateResult.acknowledged(1, 1L, null));
        service.verifyLogin(1L, code);
        verify(limits).checkAndRecord(
                eq("TOTP"), eq("1"), eq(5), eq(Duration.ofMinutes(15)), eq(Duration.ofMinutes(15)),
                anyString(), anyString());
    }

    /** Enables 2FA for user 1 and returns a currently valid TOTP code. */
    private String enabledTwoFactorWithUpdateResults(UpdateResult first, UpdateResult... rest) {
        String secret = totp.newSecret();
        AccountSecurity security = new AccountSecurity();
        security.setId(1L);
        security.setEnabled(true);
        security.setSecret(encryption.encrypt(secret));
        when(mongo.findById(1L, AccountSecurity.class)).thenReturn(security);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(AccountSecurity.class)))
                .thenReturn(first, rest);
        return totp.code(secret, Instant.now().getEpochSecond() / 30);
    }

    private void validPassword() {
        User user = new User();
        user.setId(1L);
        user.setPassword("hashed");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(passwords.matches("password", "hashed")).thenReturn(true);
    }
}
