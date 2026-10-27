package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.User;
import com.aloute.repository.IPendingUserRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Resolves a verified Firebase identity to an existing, registered ALOUTE account. */
@Service
public class FirebaseAccountProvisioningService {
    public static final String EMAIL_NOT_REGISTERED_MESSAGE =
            "Email này chưa được đăng ký trên ALOUTE. Vui lòng đăng ký tài khoản và xác thực email trước khi đăng nhập bằng Google.";
    public static final String EMAIL_PENDING_VERIFICATION_MESSAGE =
            "Email này đã được dùng để đăng ký ALOUTE nhưng chưa xác thực. Vui lòng xác thực email trước khi đăng nhập bằng Google.";

    private final IUserRepository userRepository;
    private final IPendingUserRepository pendingUserRepository;
    private final JwtUtil jwtUtil;
    public FirebaseAccountProvisioningService(
            IUserRepository userRepository,
            IPendingUserRepository pendingUserRepository,
            JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.pendingUserRepository = pendingUserRepository;
        this.jwtUtil = jwtUtil;
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

        // Google login is authentication only, not registration. Do not silently create a local
        // ALOUTE account because Firebase signInWithPopup can succeed for a new Google identity.
        if (user == null) {
            if (pendingUserRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new BadRequestException(EMAIL_PENDING_VERIFICATION_MESSAGE);
            }
            throw new BadRequestException(EMAIL_NOT_REGISTERED_MESSAGE);
        }

        if (!user.isActive() || Boolean.TRUE.equals(user.getSelfDeactivated())) {
            throw new UnauthorizedException(
                    "Tài khoản ALOUTE đã bị khóa hoặc vô hiệu hóa. Hãy liên hệ quản trị viên hoặc đăng nhập theo phương thức hiện có để xử lý.");
        }

        // Google ID-token verification has already established identity. This app uses a single
        // login step; legacy account-security records do not challenge sign-in.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
        }
        if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank())
                && safeAvatarUrl(avatarUrl) != null) {
            user.setAvatarUrl(safeAvatarUrl(avatarUrl));
        }
        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());
        return new AuthResponse(
                token, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
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
