package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.util.UsernamePolicy;
import com.aloute.config.JwtUtil;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** Maps a verified Firebase identity to the application's local account and JWT cookie response. */
@Service
public class FirebaseAccountProvisioningService {
    private final IUserRepository userRepository;
    private final IPendingUserRepository pendingUserRepository;
    private final IRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountSecurityService accountSecurity;

    public FirebaseAccountProvisioningService(
            IUserRepository userRepository,
            IPendingUserRepository pendingUserRepository,
            IRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            AccountSecurityService accountSecurity) {
        this.userRepository = userRepository;
        this.pendingUserRepository = pendingUserRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.accountSecurity = accountSecurity;
    }

    @Transactional
    public AuthResponse loginWithFirebaseProfile(
            String email, boolean emailVerified, String fullName, String avatarUrl, String twoFactorCode) {
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
            // Google proves email ownership only; an account with 2FA must still present a TOTP /
            // recovery code, exactly like the password login. Throws TwoFactorRequiredException
            // (no code) or UnauthorizedException (bad code) and nothing below is persisted.
            accountSecurity.verifyLogin(user.getId(), twoFactorCode);
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
        String base = localPart.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT);
        base = base.replaceAll("^[.-]+", "").replaceAll("[.-]+$", "");
        if (base.isBlank()) base = "google_user";
        // An email like deleted_123@gmail.com must not yield a name in the deleted-account namespace.
        if (UsernamePolicy.isReserved(base)) base = "user_" + base;
        if (base.length() < 3) base = (base + "_user");
        if (base.length() > 42) base = base.substring(0, 42);

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidate)
                || pendingUserRepository.existsByUsernameIgnoreCase(candidate)) {
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

}
