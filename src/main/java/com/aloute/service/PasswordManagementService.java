package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.common.util.TransactionCommitHooks;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/** Password reset and change flows, separated from registration/login orchestration. */
@Service
public class PasswordManagementService {
    private static final long RESET_PASSWORD_TOKEN_MINUTES = 30;
    private static final long RESET_EMAIL_COOLDOWN_SECONDS = 60;

    private final IUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final EmailService emailService;
    private PasswordResetMailDispatcher mailDispatcher;

    @Autowired
    public void setPasswordResetMailDispatcher(PasswordResetMailDispatcher dispatcher) { this.mailDispatcher = dispatcher; }
    private final RefreshTokenService refreshTokens;
    private final AuthenticationUserCache authenticationUserCache;

    @Autowired
    public PasswordManagementService(
            IUserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthTokenService authTokenService, EmailService emailService,
            RefreshTokenService refreshTokens, AuthenticationUserCache authenticationUserCache) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.emailService = emailService;

        this.refreshTokens = refreshTokens;
        this.authenticationUserCache = authenticationUserCache;
    }

    /** Compatibility constructor for tests/legacy callers. */
    public PasswordManagementService(IUserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthTokenService authTokenService, EmailService emailService,
            RefreshTokenService refreshTokens) {
        this(userRepository, passwordEncoder, authTokenService, emailService, refreshTokens, null);
    }

    public PasswordManagementService(IUserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthTokenService authTokenService, EmailService emailService) {
        this(userRepository, passwordEncoder, authTokenService, emailService, null, null);
    }

    public void forgotPassword(String email) {

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        if (normalizedEmail.isBlank()) {
            return;
        }

        userRepository
                .findByEmailIgnoreCase(normalizedEmail)
                .ifPresent(
                        user -> {
                            LocalDateTime now = LocalDateTime.now();

                            if (user.getResetPasswordToken() != null
                                    && user.getResetPasswordExpiry() != null) {

                                LocalDateTime tokenCreatedAt =
                                        user.getResetPasswordExpiry()
                                                .minusMinutes(RESET_PASSWORD_TOKEN_MINUTES);

                                if (now.isBefore(
                                        tokenCreatedAt.plusSeconds(RESET_EMAIL_COOLDOWN_SECONDS))) {

                                    return;
                                }
                            }

                            String resetToken = authTokenService.generateToken();

                            String resetTokenHash = authTokenService.hashToken(resetToken);

                            user.setResetPasswordToken(resetTokenHash);

                            user.setResetPasswordExpiry(
                                    now.plusMinutes(RESET_PASSWORD_TOKEN_MINUTES));

                            userRepository.save(user);

                            if (mailDispatcher != null) mailDispatcher.send(
                                    user.getEmail(), user.getFullName(), resetToken);
                            else emailService.sendResetPasswordEmail(
                                    user.getEmail(), user.getFullName(), resetToken);
                        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {

        String value = token == null ? "" : token.trim();

        if (value.isBlank()) {
            throw invalidResetLink();
        }

        // Only the SHA-256 digest is stored, so only the digest is looked up. Raw (plaintext) UUID
        // tokens from older versions are no longer accepted; the V20261024 migration clears them.
        String tokenHash = authTokenService.hashToken(value);

        User user =
                userRepository
                        .findByResetPasswordToken(tokenHash)
                        .orElseThrow(PasswordManagementService::invalidResetLink);

        LocalDateTime now = LocalDateTime.now();

        if (user.getResetPasswordExpiry() == null || !user.getResetPasswordExpiry().isAfter(now)) {

            throw new BadRequestException(
                    "Link đặt lại mật khẩu " + "đã hết hạn, vui lòng yêu cầu lại");
        }

        // Hash outside the conditional UPDATE so the row is not locked during the slow BCrypt work.
        String encodedPassword = passwordEncoder.encode(newPassword);
        Instant revokedAt = Instant.now();
        Long userId = user.getId();
        String username = user.getUsername();

        // Compare-and-set: succeeds for exactly one of any number of concurrent requests that
        // present the same link; the rest affect 0 rows and are rejected.
        int updated =
                userRepository.consumeResetToken(userId, tokenHash, encodedPassword, revokedAt, now);

        if (updated != 1) {
            throw invalidResetLink();
        }

        if (refreshTokens != null) refreshTokens.revokeAll(userId, revokedAt);
        invalidateAuthenticationSnapshotAfterCommit(username);
    }

    private static BadRequestException invalidResetLink() {
        return new BadRequestException("Link đặt lại mật khẩu không hợp lệ hoặc đã được dùng");
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {

            throw new BadRequestException("Mật khẩu hiện tại không đúng");
        }

        if (passwordEncoder.matches(newPassword, user.getPassword())) {

            throw new BadRequestException("Mật khẩu mới phải khác " + "mật khẩu hiện tại");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokensRevokedAt(Instant.now());
        userRepository.save(user);
        if (refreshTokens != null) refreshTokens.revokeAll(user.getId(), user.getTokensRevokedAt());
        invalidateAuthenticationSnapshotAfterCommit(user.getUsername());
    }

    private void invalidateAuthenticationSnapshotAfterCommit(String username) {
        if (authenticationUserCache != null && username != null && !username.isBlank()) {
            TransactionCommitHooks.afterCommit(() -> authenticationUserCache.invalidate(username));
        }
    }
}
