package com.aloute.service;

import com.aloute.common.exception.*;
import com.aloute.model.document.*;
import com.aloute.repository.IUserRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.IGroupMemberRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IUserPushTokenRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.common.util.UsernamePolicy;
import com.aloute.service.FirebaseAdminService;
import org.springframework.beans.factory.annotation.Autowired;


import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class AccountSecurityService {
    private static final String TOTP_LIMIT_NAMESPACE = "TOTP";
    // Failed-attempt budget: 5 tries per 15 minutes, then a 15 minute cool-down.
    private static final int TOTP_MAX_ATTEMPTS = 5;
    private static final Duration TOTP_WINDOW = Duration.ofMinutes(15);
    private static final Duration TOTP_BLOCK_DURATION = Duration.ofMinutes(15);
    private final MongoTemplate mongo;
    private final IUserRepository users;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final SecretEncryptionService encryption;
    private final AuthTokenService tokens;
    private final AuthRateLimitService limits;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;
    private final ICommunityMemberRepository communityMembers;
    private final IGroupMemberRepository groupMembers;
    private final INotificationRepository notifications;
    private final IUserPushTokenRepository pushTokens;
    private final IGroupReadStatusRepository groupReadStatuses;
    private final RefreshTokenService refreshTokens;
    private final AccountDataCleanupService accountDataCleanupService;
    private final AuthenticationUserCache authenticationUserCache;
    private final FirebaseAdminService firebaseAdminService;

    @Autowired
    public AccountSecurityService(
            MongoTemplate mongo,
            IUserRepository users,
            PasswordEncoder passwords,
            TotpService totp,
            SecretEncryptionService encryption,
            AuthTokenService tokens,
            AuthRateLimitService limits,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            ICommunityMemberRepository communityMembers,
            IGroupMemberRepository groupMembers,
            INotificationRepository notifications,
            IUserPushTokenRepository pushTokens,
            IGroupReadStatusRepository groupReadStatuses,
            RefreshTokenService refreshTokens,
            AccountDataCleanupService accountDataCleanupService,
            AuthenticationUserCache authenticationUserCache,
            FirebaseAdminService firebaseAdminService) {
        this.mongo = mongo;
        this.users = users;
        this.passwords = passwords;
        this.totp = totp;
        this.encryption = encryption;
        this.tokens = tokens;
        this.limits = limits;
        this.friends = friends;
        this.closeFriends = closeFriends;
        this.communityMembers = communityMembers;
        this.groupMembers = groupMembers;
        this.notifications = notifications;
        this.pushTokens = pushTokens;
        this.groupReadStatuses = groupReadStatuses;
        this.refreshTokens = refreshTokens;
        this.accountDataCleanupService = accountDataCleanupService;
        this.authenticationUserCache = authenticationUserCache;
        this.firebaseAdminService = firebaseAdminService;
    }

    /** Compatibility constructor retained for direct unit-test callers. */
    public AccountSecurityService(
            MongoTemplate mongo,
            IUserRepository users,
            PasswordEncoder passwords,
            TotpService totp,
            SecretEncryptionService encryption,
            AuthTokenService tokens,
            AuthRateLimitService limits,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            ICommunityMemberRepository communityMembers,
            IGroupMemberRepository groupMembers,
            INotificationRepository notifications,
            IUserPushTokenRepository pushTokens,
            IGroupReadStatusRepository groupReadStatuses,
            RefreshTokenService refreshTokens,
            AccountDataCleanupService accountDataCleanupService,
            AuthenticationUserCache authenticationUserCache) {
        this(mongo, users, passwords, totp, encryption, tokens, limits, friends, closeFriends,
                communityMembers, groupMembers, notifications, pushTokens, groupReadStatuses,
                refreshTokens, accountDataCleanupService, authenticationUserCache, null);
    }

    public AccountSecurityService(
            MongoTemplate mongo,
            IUserRepository users,
            PasswordEncoder passwords,
            TotpService totp,
            SecretEncryptionService encryption,
            AuthTokenService tokens,
            AuthRateLimitService limits,
            IFriendRepository friends,
            ICloseFriendRepository closeFriends,
            ICommunityMemberRepository communityMembers,
            IGroupMemberRepository groupMembers,
            INotificationRepository notifications,
            IUserPushTokenRepository pushTokens,
            IGroupReadStatusRepository groupReadStatuses,
            RefreshTokenService refreshTokens,
            AccountDataCleanupService accountDataCleanupService) {
        this(mongo, users, passwords, totp, encryption, tokens, limits, friends, closeFriends,
                communityMembers, groupMembers, notifications, pushTokens, groupReadStatuses,
                refreshTokens, accountDataCleanupService, null);
    }

    public void checkPassword(Long user, String password) {
        checkPassword(user, password, null);
    }

    /**
     * Re-authenticates sensitive account actions with either a local password or a fresh,
     * server-verified Google ID token. A Google token is never accepted on its own without
     * verifying provider, email ownership, revocation and recent auth_time in Firebase Admin.
     */
    public void checkPassword(Long user, String password, String firebaseIdToken) {
        limits.checkAndRecord(
                "ACCOUNT_SECURITY",
                user.toString(),
                10,
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                "Thử lại sau %d phút",
                "Quá nhiều yêu cầu");
        var account = users.findById(user).orElseThrow();
        if (password != null && !password.isBlank()
                && passwords.matches(password, account.getPassword())) {
            return;
        }
        if (firebaseAdminService != null
                && firebaseAdminService.verifyRecentGoogleReauthentication(firebaseIdToken, account.getEmail())) {
            return;
        }
        throw new UnauthorizedException(
                "Mật khẩu không đúng. Nếu bạn dùng Google, hãy xác minh lại bằng Google trong cửa sổ hiện ra.");
    }

    public boolean enabled(Long user) {
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        return security != null && security.isEnabled();
    }

    public void verifyLogin(Long user, String code) {
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null || !security.isEnabled()) return;
        if (code == null || code.isBlank()) throw new TwoFactorRequiredException();
        // Reserve an attempt slot BEFORE verifying so parallel guesses cannot all slip past the
        // limiter, then give the budget back (reset) once a valid code or recovery code is
        // accepted. Only failed attempts therefore accumulate toward the temporary block.
        final String limiterSubject = user.toString();
        limits.checkAndRecord(
                TOTP_LIMIT_NAMESPACE,
                limiterSubject,
                TOTP_MAX_ATTEMPTS,
                TOTP_WINDOW,
                TOTP_BLOCK_DURATION,
                "Bạn đã nhập sai mã xác thực quá nhiều lần. Thử lại sau khoảng %d phút.",
                "Quá nhiều mã xác thực sai. Tính năng xác thực 2FA tạm khóa 15 phút.");
        long step = totp.verify(encryption.decrypt(security.getSecret()), code);
        if (step >= 0
                && mongo.updateFirst(
                                        Query.query(
                                                Criteria.where("_id")
                                                        .is(user)
                                                        .and("enabled")
                                                        .is(true)
                                                        .and("secret")
                                                        .is(security.getSecret())
                                                        .and("lastStep")
                                                        .lt(step)),
                                        new Update().set("lastStep", step),
                                        AccountSecurity.class)
                                .getModifiedCount()
                        == 1) {
            clearTotpFailures(limiterSubject);
            return;
        }
        if (code != null
                && mongo.updateFirst(
                                        Query.query(
                                                Criteria.where("_id")
                                                        .is(user)
                                                        .and("enabled")
                                                        .is(true)
                                                        .and("recoveryHashes")
                                                        .is(tokens.hashToken(code))),
                                        new Update().pull("recoveryHashes", tokens.hashToken(code)),
                                        AccountSecurity.class)
                                .getModifiedCount()
                        == 1) {
            clearTotpFailures(limiterSubject);
            return;
        }
        throw new UnauthorizedException("Cần mã 2FA hợp lệ hoặc mã khôi phục chưa sử dụng");
    }

    /**
     * Clears the failed-attempt counter after a successful second factor. Best effort: the one-time
     * code/recovery code has already been consumed at this point, so a limiter outage must not turn a
     * valid verification into an error.
     */
    private void clearTotpFailures(String limiterSubject) {
        try {
            limits.reset(TOTP_LIMIT_NAMESPACE, limiterSubject);
        } catch (RuntimeException resetFailure) {
            org.slf4j.LoggerFactory.getLogger(AccountSecurityService.class)
                    .warn("Unable to reset 2FA attempt counter (cause={})",
                            resetFailure.getClass().getSimpleName());
        }
    }

    public Map<String, String> setup(Long user, String password) {
        return setup(user, password, null);
    }

    public Map<String, String> setup(Long user, String password, String firebaseIdToken) {
        checkPassword(user, password, firebaseIdToken);
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null) {
            security = new AccountSecurity();
            security.setId(user);
            try {
                mongo.insert(security);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw new BadRequestException("Thiết lập vừa thay đổi. Hãy thử lại.");
            }
        }
        if (security.isEnabled()) throw new BadRequestException("2FA đã được bật");
        String secret = totp.newSecret();
        long changed =
                mongo.updateFirst(
                                Query.query(
                                        Criteria.where("_id").is(user).and("enabled").is(false)),
                                new Update()
                                        .set("pendingSecret", encryption.encrypt(secret))
                                        .set("pendingExpiresAt", Instant.now().plusSeconds(600)),
                                AccountSecurity.class)
                        .getMatchedCount();
        if (changed == 0)
            throw new BadRequestException("2FA đã được bật hoặc thiết lập đã thay đổi");
        return Map.of(
                "secret",
                secret,
                "uri",
                "otpauth://totp/Aloute:"
                        + user
                        + "?secret="
                        + secret
                        + "&issuer=Aloute&digits=6&period=30");
    }

    public List<String> enable(Long user, String password, String code) {
        return enable(user, password, code, null);
    }

    public List<String> enable(Long user, String password, String code, String firebaseIdToken) {
        checkPassword(user, password, firebaseIdToken);
        AccountSecurity security = mongo.findById(user, AccountSecurity.class);
        if (security == null
                || security.isEnabled()
                || security.getPendingExpiresAt() == null
                || security.getPendingExpiresAt().isBefore(Instant.now()))
            throw new BadRequestException("Hãy tạo thiết lập 2FA mới");
        long step = totp.verify(encryption.decrypt(security.getPendingSecret()), code);
        if (step < 0) throw new BadRequestException("Mã xác thực không đúng");
        List<String> recovery = new ArrayList<>();
        for (int i = 0; i < 8; i++) recovery.add(tokens.generateToken());
        long changed =
                mongo.updateFirst(
                                Query.query(
                                        Criteria.where("_id")
                                                .is(user)
                                                .and("enabled")
                                                .is(false)
                                                .and("pendingSecret")
                                                .is(security.getPendingSecret())
                                                .and("pendingExpiresAt")
                                                .gt(Instant.now())),
                                new Update()
                                        .set("secret", security.getPendingSecret())
                                        .unset("pendingSecret")
                                        .unset("pendingExpiresAt")
                                        .set("enabled", true)
                                        .set("lastStep", step)
                                        .set(
                                                "recoveryHashes",
                                                recovery.stream().map(tokens::hashToken).toList()),
                                AccountSecurity.class)
                        .getModifiedCount();
        if (changed == 0) throw new BadRequestException("Thiết lập 2FA vừa thay đổi. Hãy thử lại.");
        return recovery;
    }

    public void disable(Long user, String password, String code) {
        disable(user, password, code, null);
    }

    public void disable(Long user, String password, String code, String firebaseIdToken) {
        checkPassword(user, password, firebaseIdToken);
        verifyLogin(user, code);
        mongo.remove(Query.query(Criteria.where("_id").is(user)), AccountSecurity.class);
    }

    @org.springframework.transaction.annotation.Transactional
    public void close(Long user, String password, String code, boolean delete) {
        close(user, password, code, delete, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public void close(Long user, String password, String code, boolean delete, String firebaseIdToken) {
        checkPassword(user, password, firebaseIdToken);
        verifyLogin(user, code);
        var account = users.findById(user).orElseThrow();
        String usernameBeforeDeactivation = account.getUsername();
        String avatarUrlBeforeDeletion = delete ? account.getAvatarUrl() : null;
        String coverUrlBeforeDeletion = delete ? account.getCoverUrl() : null;
        account.setActive(false);
        account.setSelfDeactivated(!delete);
        Instant revokedAt = Instant.now();
        account.setTokensRevokedAt(revokedAt);
        refreshTokens.revokeAll(user, revokedAt);
        if (delete) {
            account.setUsername(UsernamePolicy.deletedAccountUsername(user));
            account.setEmail("deleted_" + user + "@invalid.local");
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
        }
        users.save(account);
        if (authenticationUserCache != null) {
            String usernameAfterDeactivation = account.getUsername();
            TransactionCommitHooks.afterCommit(() -> {
                authenticationUserCache.invalidate(usernameBeforeDeactivation);
                if (!Objects.equals(usernameBeforeDeactivation, usernameAfterDeactivation)) {
                    authenticationUserCache.invalidate(usernameAfterDeactivation);
                }
            });
        }
        if (delete) {
            friends.deleteByFollowerIdOrFollowedId(user, user);
            closeFriends.deleteByRequesterIdOrReceiverId(user, user);
            communityMembers.deleteByUserId(user);
            groupMembers.deleteByUserId(user);
            groupReadStatuses.deleteByUserId(user);
            pushTokens.deleteByUserId(user);
            notifications.deleteByReceiverIdOrSenderId(user, user);
            com.aloute.common.util.TransactionCommitHooks.afterCommit(() -> {
                try {
                    accountDataCleanupService.deleteUserData(
                            user, avatarUrlBeforeDeletion, coverUrlBeforeDeletion);
                } catch (RuntimeException cleanupError) {
                    org.slf4j.LoggerFactory.getLogger(AccountSecurityService.class)
                            .error("Account data cleanup failed after account deletion; scheduling retry (userId={}, cause={})",
                                    user, cleanupError.getClass().getSimpleName(), cleanupError);
                    try {
                        accountDataCleanupService.enqueueRetry(
                                user, avatarUrlBeforeDeletion, coverUrlBeforeDeletion, cleanupError);
                    } catch (RuntimeException enqueueError) {
                        org.slf4j.LoggerFactory.getLogger(AccountSecurityService.class)
                                .error("Unable to persist account cleanup retry; manual reconciliation is required (userId={}, cause={})",
                                        user, enqueueError.getClass().getSimpleName(), enqueueError);
                    }
                }
            });
        }
    }
}
