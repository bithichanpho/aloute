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
    /** How long an emailed password-reset OTP stays valid. */
    private static final long RESET_PASSWORD_TOKEN_MINUTES = 10;
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

    @Transactional
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

                            String resetOtp = authTokenService.generateOtp();

                            // Stored only as a hash bound to the email; the plain code goes out by mail.
                            String resetTokenHash =
                                    authTokenService.hashOtp(normalizedEmail, resetOtp);

                            // Update only reset state; a stale entity must not overwrite a concurrently
                            // changed password. The conditional write also enforces the send cooldown.
                            int issued=userRepository.issueResetToken(user.getId(),resetTokenHash,
                                    now.plusMinutes(RESET_PASSWORD_TOKEN_MINUTES),
                                    now.plusMinutes(RESET_PASSWORD_TOKEN_MINUTES).minusSeconds(RESET_EMAIL_COOLDOWN_SECONDS));
                            if(issued!=1)return;
                            TransactionCommitHooks.afterCommit(()->{
                                if (mailDispatcher != null) mailDispatcher.send(user.getEmail(), user.getFullName(), resetOtp);
                                else emailService.sendResetPasswordEmail(user.getEmail(), user.getFullName(), resetOtp);
                            });
                        });
    }

    /**
     * Sets a new password from the six-digit code that was emailed by {@link #forgotPassword}.
     *
     * <p>All failures about the email or the code share one message so the endpoint does not reveal
     * which emails have an account. Attempts are throttled by the controller.
     */
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        User user=verifiedOtpUser(email,code);
        consumeReset(user,user.getResetPasswordToken(),newPassword);
    }

    /** Immutable internal proof; never serialize this hash into a browser response. */
    public record ResetProof(Long userId,String tokenHash,LocalDateTime resetExpiry) {}

    @Transactional(readOnly=true)
    public ResetProof verifyResetCode(String email,String code) {
        User user=verifiedOtpUser(email,code);
        return new ResetProof(user.getId(),user.getResetPasswordToken(),user.getResetPasswordExpiry());
    }

    @Transactional(readOnly=true)
    public java.util.Optional<String> verifiedResetEmail(ResetProof proof) {
        return userRepository.findById(proof.userId()).filter(user->activeProof(user,proof))
                .map(User::getEmail);
    }

    @Transactional
    public void resetPasswordVerified(ResetProof proof,String newPassword) {
        User user=userRepository.findById(proof.userId()).filter(found->activeProof(found,proof))
                .orElseThrow(PasswordManagementService::invalidResetCode);
        consumeReset(user,proof.tokenHash(),newPassword,proof.resetExpiry());
    }

    private boolean activeProof(User user,ResetProof proof) {
        String hash=proof.tokenHash();
        return hash!=null&&user.getResetPasswordToken()!=null
                &&java.security.MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    user.getResetPasswordToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                &&user.getResetPasswordExpiry()!=null&&user.getResetPasswordExpiry().equals(proof.resetExpiry())
                &&user.getResetPasswordExpiry().isAfter(LocalDateTime.now());
    }

    private User verifiedOtpUser(String email,String code) {

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        String otp = code == null ? "" : code.trim();

        if (normalizedEmail.isBlank() || !authTokenService.isValidOtpFormat(otp)) {
            throw invalidResetCode();
        }

        User user =
                userRepository
                        .findByEmailIgnoreCase(normalizedEmail)
                        .orElseThrow(PasswordManagementService::invalidResetCode);

        String storedHash = user.getResetPasswordToken();

        if (!authTokenService.otpMatches(normalizedEmail, otp, storedHash)) {
            throw invalidResetCode();
        }

        LocalDateTime now = LocalDateTime.now();

        if (user.getResetPasswordExpiry() == null || !user.getResetPasswordExpiry().isAfter(now)) {

            throw new BadRequestException(
                    "Mã đặt lại mật khẩu " + "đã hết hạn, vui lòng yêu cầu lại");
        }
        return user;
    }

    private void consumeReset(User user,String storedHash,String newPassword) {
        consumeReset(user,storedHash,newPassword,null);
    }

    private void consumeReset(User user,String storedHash,String newPassword,LocalDateTime expectedExpiry) {
        // Hash outside the conditional UPDATE so the row is not locked during the slow BCrypt work.
        String encodedPassword = passwordEncoder.encode(newPassword);
        LocalDateTime now=LocalDateTime.now();
        Instant revokedAt = Instant.now();
        Long userId = user.getId();
        String username = user.getUsername();

        // Compare-and-set: succeeds for exactly one of any number of concurrent requests that
        // present the same code; the rest affect 0 rows and are rejected.
        int updated = expectedExpiry==null
                ? userRepository.consumeResetToken(userId, storedHash, encodedPassword, revokedAt, now)
                : userRepository.consumeVerifiedResetToken(userId,storedHash,expectedExpiry,encodedPassword,revokedAt,now);

        if (updated != 1) {
            throw invalidResetCode();
        }

        if (refreshTokens != null) refreshTokens.revokeAll(userId, revokedAt);
        invalidateAuthenticationSnapshotAfterCommit(username);
    }

    private static BadRequestException invalidResetCode() {
        return new BadRequestException("Mã đặt lại mật khẩu không hợp lệ hoặc đã được dùng");
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

    /** Optional REST field preserves older clients while validating confirmation when supplied. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword, String confirmation) {
        if (confirmation != null && !java.util.Objects.equals(newPassword, confirmation)) {
            throw new BadRequestException("Mật khẩu mới và phần nhập lại không khớp");
        }
        changePassword(userId, currentPassword, newPassword);
    }
}
