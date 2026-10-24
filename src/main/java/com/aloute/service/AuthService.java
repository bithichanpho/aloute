package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.EmailDeliveryException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.util.UsernamePolicy;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.PendingUser;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    /** How long an emailed verification OTP stays valid. */
    private static final long VERIFICATION_TOKEN_MINUTES = 10;
    private final String missingUserPasswordHash;

    private static final long PENDING_RETENTION_HOURS = 24;

    private final AccountSecurityService accountSecurity;
    private final IUserRepository userRepository;
    private final IPendingUserRepository pendingUserRepository;
    private final IRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    private final AuthTokenService authTokenService;
    private final PasswordManagementService passwordManagementService;
    private final FirebaseAccountProvisioningService firebaseAccountProvisioningService;
    private final AuthRateLimitService rateLimits;

    public AuthService(
            IUserRepository userRepository,
            IPendingUserRepository pendingUserRepository,
            IRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            EmailService emailService,
            AuthTokenService authTokenService,
            AccountSecurityService accountSecurity,
            PasswordManagementService passwordManagementService,
            FirebaseAccountProvisioningService firebaseAccountProvisioningService,
            AuthRateLimitService rateLimits) {
        this.accountSecurity = accountSecurity;
        this.rateLimits = rateLimits;

        this.userRepository = userRepository;
        this.pendingUserRepository = pendingUserRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.missingUserPasswordHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.authTokenService = authTokenService;
        this.passwordManagementService = passwordManagementService;
        this.firebaseAccountProvisioningService = firebaseAccountProvisioningService;
    }

    private static final String USERNAME_TAKEN = "Tên đăng nhập đã được sử dụng";

    /**
     * Registers an account without revealing whether the email already has one.
     *
     * <p>Usernames are public handles (profile URLs, search results), so reporting one as taken
     * leaks nothing private and the person needs it to choose another. Email addresses are private:
     * from that point on, every outcome (new email, email with an account, email with a pending
     * registration) returns the same response. The owner of an existing account is told by email
     * instead. The password is hashed on every path so that response time does not give it away.
     */
    public AuthResponse register(RegisterRequest request) {

        String username = request.getUsername().trim().toLowerCase(Locale.ROOT);

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        String fullName = request.getFullName().trim();

        if (UsernamePolicy.isReserved(username)) {
            throw new BadRequestException("Tên đăng nhập này không khả dụng");
        }

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BadRequestException(USERNAME_TAKEN);
        }

        LocalDateTime now = LocalDateTime.now();

        var pendingByUsername = pendingUserRepository.findByUsernameIgnoreCase(username);

        var pendingByEmail = pendingUserRepository.findByEmailIgnoreCase(email);

        // A live pending registration for this username under a different email holds the handle.
        if (pendingByUsername
                .filter(pending -> pending.getExpiresAt().isAfter(now))
                .filter(pending -> !email.equalsIgnoreCase(pending.getEmail()))
                .isPresent()) {
            throw new BadRequestException(USERNAME_TAKEN);
        }

        // Do the expensive work before branching on the email, so every path costs about the same.
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        AuthResponse accepted = new AuthResponse(null, null, username, fullName, null);

        var existingAccount = userRepository.findByEmailIgnoreCase(email);

        if (existingAccount.isPresent()) {
            notifyAccountAlreadyExists(existingAccount.get());
            return accepted;
        }

        if (pendingByEmail.filter(pending -> pending.getExpiresAt().isAfter(now)).isPresent()) {
            // Same person registering again while still unverified: send a fresh code
            // (at most once a minute). Never replace the pending data, or someone who knows the
            // email could overwrite its owner's pending registration.
            resendPendingIfAllowed(pendingByEmail.get(), now);
            return accepted;
        }

        removeExpiredPending(pendingByUsername, pendingByEmail);

        String verificationOtp = authTokenService.generateOtp();

        // The OTP is stored only as a hash bound to the email; the plain code goes out by mail.
        String verificationTokenHash = authTokenService.hashOtp(email, verificationOtp);

        PendingUser pending = new PendingUser();

        pending.setFullName(fullName);
        pending.setUsername(username);
        pending.setEmail(email);

        pending.setPassword(encodedPassword);

        pending.setVerificationToken(verificationTokenHash);

        pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        pending.setVerificationEmailSentAt(now);

        pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

        PendingUser saved;

        try {

            saved = pendingUserRepository.save(pending);

        } catch (DataIntegrityViolationException exception) {

            // Only reachable when an identical request races this one. Do not say which field.
            throw new BadRequestException(
                    "Không thể hoàn tất đăng ký lúc này, vui lòng thử lại sau ít phút");
        }

        try {
            emailService.sendVerificationEmail(
                    saved.getEmail(), saved.getFullName(), verificationOtp);
        } catch (EmailDeliveryException ex) {

            saved.setVerificationEmailSentAt(now.minusMinutes(1));
            pendingUserRepository.save(saved);
            throw ex;
        }

        return accepted;
    }

    /** Tells the owner someone tried to register their email. Throttled to one mail a minute. */
    private void notifyAccountAlreadyExists(User existing) {
        try {
            rateLimits.checkAndRecord(
                    "REGISTER_EXISTING_NOTICE",
                    existing.getEmail(),
                    1,
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(1),
                    "%d",
                    "throttled");
        } catch (RuntimeException throttledOrLimiterDown) {
            return; // Skip silently: the response must look the same, and the owner is not spammed.
        }
        emailService.sendAccountAlreadyExistsEmail(existing.getEmail(), existing.getFullName());
    }

    private void removeExpiredPending(
            Optional<PendingUser> byUsername, Optional<PendingUser> byEmail) {

        byUsername.ifPresent(pendingUserRepository::delete);

        byEmail.filter(
                        pending ->
                                byUsername.isEmpty()
                                        || !pending.getId().equals(byUsername.get().getId()))
                .ifPresent(pendingUserRepository::delete);
    }

    public AuthResponse login(LoginRequest request) {

        String identifier = request.getUsername().trim();

        var userOptional =
                userRepository
                        .findByUsernameIgnoreCase(identifier)
                        .or(() -> userRepository.findByEmailIgnoreCase(identifier));

        if (userOptional.isEmpty()) {

            var pendingOptional =
                    pendingUserRepository
                            .findByUsernameIgnoreCase(identifier)
                            .or(() -> pendingUserRepository.findByEmailIgnoreCase(identifier));

            if (pendingOptional.isPresent()
                    && passwordEncoder.matches(
                            request.getPassword(), pendingOptional.get().getPassword())) {

                throw new UnauthorizedException("Vui lòng xác thực email " + "trước khi đăng nhập");
            }

            // Equalize the expensive password-hash verification path for unknown identities.
            passwordEncoder.matches(request.getPassword(), missingUserPasswordHash);
            throw new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu");
        }

        User user = userOptional.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {

            throw new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu");
        }

        boolean reactivated = Boolean.TRUE.equals(user.getSelfDeactivated());
        if (reactivated) {
            accountSecurity.verifyLogin(user.getId(), request.getCode());
            user.setActive(true);
            user.setSelfDeactivated(false);
            userRepository.save(user);
        } else if (!user.isActive()) {

            throw new UnauthorizedException("Tài khoản đã bị khóa");
        }

        if (!user.isEmailVerified()) {

            throw new UnauthorizedException("Vui lòng xác thực email " + "trước khi đăng nhập");
        }

        if (!reactivated) accountSecurity.verifyLogin(user.getId(), request.getCode());
        String token =
                jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());

        return new AuthResponse(
                token, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
    }

    public AuthResponse loginWithFirebaseProfile(
            String email, boolean emailVerified, String fullName, String avatarUrl, String twoFactorCode) {
        return firebaseAccountProvisioningService.loginWithFirebaseProfile(
                email, emailVerified, fullName, avatarUrl, twoFactorCode);
    }

    /**
     * Activates an account from the six-digit code that was emailed at registration.
     *
     * <p>Every failure about the code or the email gives the same message, so the endpoint does not
     * reveal which emails have a pending registration. Attempts are throttled by the controller.
     */
    @Transactional
    public void verifyEmail(String email, String code) {

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

        String otp = code == null ? "" : code.trim();

        if (normalizedEmail.isBlank() || !authTokenService.isValidOtpFormat(otp)) {

            throw invalidVerificationCode();
        }

        var pendingOptional = pendingUserRepository.findByEmailIgnoreCase(normalizedEmail);

        if (pendingOptional.isEmpty()) {

            verifyExistingUser(normalizedEmail, otp);
            return;
        }

        PendingUser pending = pendingOptional.get();

        if (!authTokenService.otpMatches(normalizedEmail, otp, pending.getVerificationToken())) {

            throw invalidVerificationCode();
        }

        if (pending.getVerificationTokenExpiry() == null
                || !pending.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

            throw new BadRequestException(
                    "Mã xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại mã");
        }

        if (userRepository.existsByUsernameIgnoreCase(pending.getUsername())
                || userRepository.existsByEmailIgnoreCase(pending.getEmail())) {

            throw new BadRequestException("Tên đăng nhập hoặc email đã được sử dụng");
        }

        Role userRole =
                roleRepository
                        .findByName("ROLE_USER")
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Chưa seed role ROLE_USER"));

        User user = new User();

        user.setFullName(pending.getFullName());

        user.setUsername(pending.getUsername());

        user.setEmail(pending.getEmail());

        user.setPassword(pending.getPassword());

        user.setRole(userRole);

        user.setActive(true);

        user.setEmailVerified(true);

        userRepository.save(user);

        pendingUserRepository.delete(pending);
    }

    private static BadRequestException invalidVerificationCode() {
        return new BadRequestException("Mã xác thực không hợp lệ hoặc đã được dùng");
    }

    @Transactional
    public void resendVerificationEmail(String identifier) {

        String value = identifier == null ? "" : identifier.trim();

        var pendingOptional =
                pendingUserRepository
                        .findByUsernameIgnoreCase(value)
                        .or(() -> pendingUserRepository.findByEmailIgnoreCase(value));

        if (pendingOptional.isPresent()) {

            resendPendingVerification(pendingOptional.get());

            return;
        }

        var userOptional =
                userRepository
                        .findByUsernameIgnoreCase(value)
                        .or(() -> userRepository.findByEmailIgnoreCase(value));

        if (userOptional.isEmpty() || userOptional.get().isEmailVerified()) {

            return;
        }

        User user = userOptional.get();

        LocalDateTime now = LocalDateTime.now();

        LocalDateTime lastSent = user.getVerificationEmailSentAt();

        if (lastSent != null && now.isBefore(lastSent.plusMinutes(1))) {

            throw new BadRequestException("Vui lòng chờ 60 giây " + "trước khi gửi lại email");
        }

        String newOtp = authTokenService.generateOtp();

        user.setVerificationToken(authTokenService.hashOtp(user.getEmail(), newOtp));

        user.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        user.setVerificationEmailSentAt(now);

        userRepository.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), newOtp);
    }

    private void resendPendingVerification(PendingUser pending) {

        LocalDateTime now = LocalDateTime.now();

        if (!pending.getExpiresAt().isAfter(now)) {

            pendingUserRepository.delete(pending);

            throw new BadRequestException("Đăng ký đã hết hạn, " + "vui lòng đăng ký lại");
        }

        LocalDateTime lastSent = pending.getVerificationEmailSentAt();

        if (lastSent != null && now.isBefore(lastSent.plusMinutes(1))) {

            throw new BadRequestException("Vui lòng chờ 60 giây " + "trước khi gửi lại email");
        }

        sendFreshPendingVerification(pending, now);
    }

    /** Like {@link #resendPendingVerification} but silent when the 60-second cooldown applies. */
    private void resendPendingIfAllowed(PendingUser pending, LocalDateTime now) {

        LocalDateTime lastSent = pending.getVerificationEmailSentAt();

        if (lastSent != null && now.isBefore(lastSent.plusMinutes(1))) {
            return;
        }

        sendFreshPendingVerification(pending, now);
    }

    private void sendFreshPendingVerification(PendingUser pending, LocalDateTime now) {

        String newOtp = authTokenService.generateOtp();

        pending.setVerificationToken(authTokenService.hashOtp(pending.getEmail(), newOtp));

        pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        pending.setVerificationEmailSentAt(now);

        pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

        pendingUserRepository.save(pending);

        emailService.sendVerificationEmail(pending.getEmail(), pending.getFullName(), newOtp);
    }

    /** Verifies the OTP of an already-created account whose email is still unverified. */
    private void verifyExistingUser(String normalizedEmail, String otp) {

        User user =
                userRepository
                        .findByEmailIgnoreCase(normalizedEmail)
                        .filter(candidate -> !candidate.isEmailVerified())
                        .orElseThrow(AuthService::invalidVerificationCode);

        if (!authTokenService.otpMatches(normalizedEmail, otp, user.getVerificationToken())) {

            throw invalidVerificationCode();
        }

        if (user.getVerificationTokenExpiry() == null
                || !user.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

            throw new BadRequestException(
                    "Mã xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại mã");
        }

        user.setEmailVerified(true);

        user.setVerificationToken(null);

        user.setVerificationTokenExpiry(null);

        user.setVerificationEmailSentAt(null);

        userRepository.save(user);
    }

    public void forgotPassword(String email) {
        passwordManagementService.forgotPassword(email);
    }

    public void resetPassword(String email, String code, String newPassword) {
        passwordManagementService.resetPassword(email, code, newPassword);
    }

    public void changePassword(Long userId, String currentPassword, String newPassword) {
        passwordManagementService.changePassword(userId, currentPassword, newPassword);
    }

}
