package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.response.AuthResponse;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class FirebaseIdentityService {

    private final FirebaseAdminService firebaseAdminService;
    private final AuthService authService;

    public FirebaseIdentityService(
            FirebaseAdminService firebaseAdminService, AuthService authService) {
        this.firebaseAdminService = firebaseAdminService;
        this.authService = authService;
    }

    public AuthResponse loginWithGoogle(String idToken, String twoFactorCode) {
        if (!firebaseAdminService.isAuthEnabled()) {
            throw new BadRequestException(
                    "Firebase chưa được cấu hình ở backend. Hãy làm theo FIREBASE_SETUP.md.");
        }

        try {
            FirebaseToken token = firebaseAdminService.verifyIdToken(idToken);
            Map<String, Object> claims = token.getClaims();
            Object firebaseClaim = claims.get("firebase");
            String provider = null;
            if (firebaseClaim instanceof Map<?, ?> info) {
                Object value = info.get("sign_in_provider");
                if (value != null) provider = value.toString();
            }
            if (!"google.com".equals(provider)) {
                throw new UnauthorizedException("Vui lòng đăng nhập bằng tài khoản Google");
            }

            String email = claimString(claims, "email");
            boolean emailVerified = Boolean.TRUE.equals(claims.get("email_verified"));
            if (email == null || email.isBlank() || !emailVerified) {
                throw new UnauthorizedException(
                        "Tài khoản Google cần có email đã xác minh để đăng nhập ALOUTE");
            }

            return authService.loginWithFirebaseProfile(
                    email,
                    emailVerified,
                    claimString(claims, "name"),
                    claimString(claims, "picture"),
                    twoFactorCode);
        } catch (FirebaseAuthException ex) {
            throw new UnauthorizedException("Phiên đăng nhập Google không hợp lệ hoặc đã hết hạn");
        }
    }

    private String claimString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        return value == null ? null : value.toString();
    }
}
