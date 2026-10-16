package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
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

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final long VERIFICATION_TOKEN_MINUTES = 15;

    private static final long PENDING_RETENTION_HOURS = 24;

    private static final long RESET_PASSWORD_TOKEN_MINUTES = 30;

    private static final long RESET_EMAIL_COOLDOWN_SECONDS = 60;

    private final AccountSecurityService accountSecurity;
    private final IUserRepository userRepository;
    private final IPendingUserRepository pendingUserRepository;
    private final IRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    private final AuthTokenService authTokenService;

    public AuthService(
            IUserRepository userRepository,
            IPendingUserRepository pendingUserRepository,
            IRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            EmailService emailService,
            AuthTokenService authTokenService,
            AccountSecurityService accountSecurity) {
        this.accountSecurity = accountSecurity;

        this.userRepository = userRepository;
        this.pendingUserRepository = pendingUserRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.authTokenService = authTokenService;
    }

    public AuthResponse register(RegisterRequest request) {

        String username = request.getUsername().trim();

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        String fullName = request.getFullName().trim();

        if (userRepository.existsByUsername(username)) {
            throw new BadRequestException("Tên đăng nhập đã được sử dụng");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("Email đã được sử dụng");
        }

        LocalDateTime now = LocalDateTime.now();

        removeExpiredPendingConflictsOrThrow(username, email, now);

        String verificationToken = authTokenService.generateToken();

        String verificationTokenHash = authTokenService.hashToken(verificationToken);

        PendingUser pending = new PendingUser();

        pending.setFullName(fullName);
        pending.setUsername(username);
        pending.setEmail(email);

        pending.setPassword(passwordEncoder.encode(request.getPassword()));

        pending.setVerificationToken(verificationTokenHash);

        pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        pending.setVerificationEmailSentAt(now);

        pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

        PendingUser saved;

        try {

            saved = pendingUserRepository.save(pending);

        } catch (DataIntegrityViolationException exception) {

            throw new BadRequestException("Tên đăng nhập hoặc email đã được sử dụng");
        }

        try {
            emailService.sendVerificationEmail(
                    saved.getEmail(), saved.getFullName(), verificationToken);
        } catch (com.aloute.common.exception.EmailDeliveryException ex) {

            saved.setVerificationEmailSentAt(now.minusMinutes(1));
            pendingUserRepository.save(saved);
            throw ex;
        }

        return new AuthResponse(null, null, saved.getUsername(), saved.getFullName(), null);
    }

    private void removeExpiredPendingConflictsOrThrow(
            String username, String email, LocalDateTime now) {

        var byUsername = pendingUserRepository.findByUsername(username);

        var byEmail = pendingUserRepository.findByEmailIgnoreCase(email);

        boolean hasLiveConflict =
                byUsername.map(pending -> pending.getExpiresAt().isAfter(now)).orElse(false)
                        || byEmail.map(pending -> pending.getExpiresAt().isAfter(now))
                                .orElse(false);

        if (hasLiveConflict) {

            throw new BadRequestException(
                    "Đăng ký đang chờ xác thực. " + "Hãy dùng chức năng gửi lại email xác thực");
        }

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
                        .findByUsername(identifier)
                        .or(() -> userRepository.findByEmailIgnoreCase(identifier));

        if (userOptional.isEmpty()) {

            var pendingOptional =
                    pendingUserRepository
                            .findByUsername(identifier)
                            .or(() -> pendingUserRepository.findByEmailIgnoreCase(identifier));

            if (pendingOptional.isPresent()
                    && passwordEncoder.matches(
                            request.getPassword(), pendingOptional.get().getPassword())) {

                throw new UnauthorizedException("Vui lòng xác thực email " + "trước khi đăng nhập");
            }

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

    @Transactional
    public AuthResponse loginWithFirebaseProfile(
            String email, boolean emailVerified, String fullName, String avatarUrl) {
        if (email == null || email.isBlank() || !emailVerified) {
            throw new UnauthorizedException(
                    "Tài khoản Google cần có email đã xác minh để đăng nhập ALOUTE");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        if (user == null) {
            Role userRole =
                    roleRepository
                            .findByName("ROLE_USER")
                            .orElseThrow(
                                    () -> new ResourceNotFoundException(
                                            "Chưa seed role ROLE_USER"));
            String username = generateGoogleUsername(normalizedEmail);
            User created = new User();
            created.setUsername(username);
            created.setEmail(normalizedEmail);
            created.setFullName(
                    fullName == null || fullName.isBlank()
                            ? username
                            : fullName.trim().substring(0, Math.min(100, fullName.trim().length())));
            created.setPassword(passwordEncoder.encode("firebase:" + UUID.randomUUID() + ":" + UUID.randomUUID()));
            created.setRole(userRole);
            created.setActive(true);
            created.setEmailVerified(true);
            created.setAvatarUrl(safeAvatarUrl(avatarUrl));
            user = userRepository.save(created);
        } else {
            if (!user.isActive() || Boolean.TRUE.equals(user.getSelfDeactivated())) {
                throw new UnauthorizedException(
                        "Tài khoản ALOUTE đã bị khóa hoặc vô hiệu hóa. Hãy liên hệ quản trị viên hoặc đăng nhập theo phương thức hiện có để xử lý.");
            }
            if (!user.isEmailVerified()) {
                user.setEmailVerified(true);
            }
            if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank())
                    && safeAvatarUrl(avatarUrl) != null) {
                user.setAvatarUrl(safeAvatarUrl(avatarUrl));
            }
            user = userRepository.save(user);
        }

        String token =
                jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());
        return new AuthResponse(
                token, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
    }

    private String generateGoogleUsername(String email) {
        String localPart = email.substring(0, email.indexOf('@'));
        String base = localPart.replaceAll("[^A-Za-z0-9_.-]", "_");
        base = base.replaceAll("^[.-]+", "").replaceAll("[.-]+$", "");
        if (base.isBlank()) base = "google_user";
        if (base.length() < 3) base = (base + "_user");
        if (base.length() > 42) base = base.substring(0, 42);

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)
                || pendingUserRepository.existsByUsername(candidate)) {
            String tail = "_" + suffix++;
            int maxBaseLength = 50 - tail.length();
            candidate = base.substring(0, Math.min(base.length(), maxBaseLength)) + tail;
        }
        return candidate;
    }

    private String safeAvatarUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return value.trim().length() <= 500 ? value.trim() : null;
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed provider picture URLs.
        }
        return null;
    }

    @Transactional
    public void verifyEmail(String token) {

        String value = token == null ? "" : token.trim();

        if (value.isBlank()) {

            throw new BadRequestException("Link xác thực không hợp lệ");
        }

        String tokenHash = authTokenService.hashToken(value);

        var pendingOptional = pendingUserRepository.findByVerificationToken(tokenHash);

        if (pendingOptional.isEmpty() && isLegacyUuidToken(value)) {

            pendingOptional = pendingUserRepository.findByVerificationToken(value);
        }

        if (pendingOptional.isEmpty()) {

            verifyLegacyUser(value);
            return;
        }

        PendingUser pending = pendingOptional.get();

        if (pending.getVerificationTokenExpiry() == null
                || !pending.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

            throw new BadRequestException(
                    "Link xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại email");
        }

        if (userRepository.existsByUsername(pending.getUsername())
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

    @Transactional
    public void resendVerificationEmail(String identifier) {

        String value = identifier == null ? "" : identifier.trim();

        var pendingOptional =
                pendingUserRepository
                        .findByUsername(value)
                        .or(() -> pendingUserRepository.findByEmailIgnoreCase(value));

        if (pendingOptional.isPresent()) {

            resendPendingVerification(pendingOptional.get());

            return;
        }

        var userOptional =
                userRepository
                        .findByUsername(value)
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

        String newToken = authTokenService.generateToken();

        user.setVerificationToken(authTokenService.hashToken(newToken));

        user.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        user.setVerificationEmailSentAt(now);

        userRepository.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), newToken);
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

        String newToken = authTokenService.generateToken();

        pending.setVerificationToken(authTokenService.hashToken(newToken));

        pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

        pending.setVerificationEmailSentAt(now);

        pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

        pendingUserRepository.save(pending);

        emailService.sendVerificationEmail(pending.getEmail(), pending.getFullName(), newToken);
    }

    private void verifyLegacyUser(String token) {

        if (token == null || token.isBlank()) {

            throw new BadRequestException("Link xác thực không hợp lệ " + "hoặc đã được dùng");
        }

        String tokenHash = authTokenService.hashToken(token);

        var userOptional = userRepository.findByVerificationToken(tokenHash);

        if (userOptional.isEmpty() && isLegacyUuidToken(token)) {

            userOptional = userRepository.findByVerificationToken(token);
        }

        User user =
                userOptional.orElseThrow(
                        () ->
                                new BadRequestException(
                                        "Link xác thực không hợp lệ " + "hoặc đã được dùng"));

        if (user.getVerificationTokenExpiry() == null
                || !user.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

            throw new BadRequestException(
                    "Link xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại email");
        }

        user.setEmailVerified(true);

        user.setVerificationToken(null);

        user.setVerificationTokenExpiry(null);

        user.setVerificationEmailSentAt(null);

        userRepository.save(user);
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

                            emailService.sendResetPasswordEmail(
                                    user.getEmail(), user.getFullName(), resetToken);
                        });
    }

    public void resetPassword(String token, String newPassword) {

        String value = token == null ? "" : token.trim();

        if (value.isBlank()) {

            throw new BadRequestException(
                    "Link đặt lại mật khẩu không hợp lệ " + "hoặc đã được dùng");
        }

        String tokenHash = authTokenService.hashToken(value);

        var userOptional = userRepository.findByResetPasswordToken(tokenHash);

        if (userOptional.isEmpty() && isLegacyUuidToken(value)) {

            userOptional = userRepository.findByResetPasswordToken(value);
        }

        User user =
                userOptional.orElseThrow(
                        () ->
                                new BadRequestException(
                                        "Link đặt lại mật khẩu "
                                                + "không hợp lệ hoặc đã được dùng"));

        if (user.getResetPasswordExpiry() == null
                || user.getResetPasswordExpiry().isBefore(LocalDateTime.now())) {

            throw new BadRequestException(
                    "Link đặt lại mật khẩu " + "đã hết hạn, vui lòng yêu cầu lại");
        }

        user.setPassword(passwordEncoder.encode(newPassword));

        user.setResetPasswordToken(null);

        user.setResetPasswordExpiry(null);

        userRepository.save(user);
    }

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

        userRepository.save(user);
    }

    private boolean isLegacyUuidToken(String token) {

        if (token == null || token.isBlank()) {

            return false;
        }

        try {

            UUID.fromString(token);

            return true;

        } catch (IllegalArgumentException exception) {

            return false;
        }
    }
}
