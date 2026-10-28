package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.common.util.UsernamePolicy;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.NotificationAccountCleanup;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Handles sensitive account lifecycle actions; the former TOTP/2FA module has been removed. */
@Service
public class AccountSecurityService {
    private final IUserRepository users;
    private final PasswordEncoder passwords;
    private final AuthRateLimitService limits;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;
    private final ICommunityMemberRepository communityMembers;
    private final IGroupMemberRepository groupMembers;
    private final NotificationAccountCleanup notificationCleanup;
    private final IGroupReadStatusRepository groupReadStatuses;
    private final RefreshTokenService refreshTokens;
    private final AccountDataCleanupService accountDataCleanupService;
    private final AuthenticationUserCache authenticationUserCache;

    public AccountSecurityService(
            IUserRepository users,
            PasswordEncoder passwords,
            AuthRateLimitService limits,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            ICommunityMemberRepository communityMembers,
            IGroupMemberRepository groupMembers,
            NotificationAccountCleanup notificationCleanup,
            IGroupReadStatusRepository groupReadStatuses,
            RefreshTokenService refreshTokens,
            AccountDataCleanupService accountDataCleanupService,
            AuthenticationUserCache authenticationUserCache) {
        this.users = users;
        this.passwords = passwords;
        this.limits = limits;
        this.friends = friends;
        this.closeFriends = closeFriends;
        this.communityMembers = communityMembers;
        this.groupMembers = groupMembers;
        this.notificationCleanup = notificationCleanup;
        this.groupReadStatuses = groupReadStatuses;
        this.refreshTokens = refreshTokens;
        this.accountDataCleanupService = accountDataCleanupService;
        this.authenticationUserCache = authenticationUserCache;
    }

    /** Verifies the user's current local password without opening an external re-authentication flow. */
    public void checkPassword(Long userId, String password) {
        limits.checkAndRecord(
                "ACCOUNT_SECURITY",
                userId.toString(),
                10,
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                "Thử lại sau %d phút",
                "Quá nhiều yêu cầu");
        User account = users.findById(userId).orElseThrow();
        if (password != null && !password.isBlank() && account.getPassword() != null
                && passwords.matches(password, account.getPassword())) {
            return;
        }
        throw new UnauthorizedException(
                "Mật khẩu hiện tại không đúng. Nếu bạn đăng nhập bằng Google và chưa thiết lập mật khẩu, hãy đặt mật khẩu trước khi thực hiện thao tác này.");
    }

    @Transactional
    public void deleteAccount(Long userId, String password, String confirmPassword) {
        if (!Objects.equals(password, confirmPassword)) {
            throw new BadRequestException("Mật khẩu nhập lại không khớp. Vui lòng kiểm tra và nhập lại.");
        }
        checkPassword(userId, password);
        User account = users.findById(userId).orElseThrow();
        String usernameBeforeDeletion = account.getUsername();
        String avatarUrlBeforeDeletion = account.getAvatarUrl();
        String coverUrlBeforeDeletion = account.getCoverUrl();
        account.setActive(false);
        account.setSelfDeactivated(false);
        Instant revokedAt = Instant.now();
        account.setTokensRevokedAt(revokedAt);
        refreshTokens.revokeAll(userId, revokedAt);

        account.setUsername(UsernamePolicy.deletedAccountUsername(userId));
        account.setEmail("deleted_" + userId + "@invalid.local");
        account.setFullName("Tài khoản đã xóa");
        account.setBio(null);
        account.setAvatarUrl(null);
        account.setCoverUrl(null);
        account.setPassword(passwords.encode(UUID.randomUUID().toString()));
        account.setResetPasswordToken(null);
        account.setResetPasswordExpiry(null);
        account.setVerificationToken(null);
        account.setVerificationTokenExpiry(null);
        account.setVerificationEmailSentAt(null);
        users.save(account);

        if (authenticationUserCache != null) {
            String usernameAfterDeletion = account.getUsername();
            TransactionCommitHooks.afterCommit(() -> {
                authenticationUserCache.invalidate(usernameBeforeDeletion);
                if (!Objects.equals(usernameBeforeDeletion, usernameAfterDeletion)) {
                    authenticationUserCache.invalidate(usernameAfterDeletion);
                }
            });
        }

        friends.deleteByFollowerIdOrFollowedId(userId, userId);
        closeFriends.deleteByRequesterIdOrReceiverId(userId, userId);
        communityMembers.deleteByUserId(userId);
        groupMembers.deleteByUserId(userId);
        groupReadStatuses.deleteByUserId(userId);
        notificationCleanup.removeAccountReferences(userId);
        TransactionCommitHooks.afterCommit(() -> {
            try {
                accountDataCleanupService.deleteUserData(
                        userId, avatarUrlBeforeDeletion, coverUrlBeforeDeletion);
            } catch (RuntimeException cleanupError) {
                org.slf4j.LoggerFactory.getLogger(AccountSecurityService.class)
                        .error("Account data cleanup failed after account deletion; scheduling retry (userId={}, cause={})",
                                userId, cleanupError.getClass().getSimpleName(), cleanupError);
                try {
                    accountDataCleanupService.enqueueRetry(
                            userId, avatarUrlBeforeDeletion, coverUrlBeforeDeletion, cleanupError);
                } catch (RuntimeException enqueueError) {
                    org.slf4j.LoggerFactory.getLogger(AccountSecurityService.class)
                            .error("Unable to persist account cleanup retry; manual reconciliation is required (userId={}, cause={})",
                                    userId, enqueueError.getClass().getSimpleName(), enqueueError);
                }
            }
        });
    }
}
