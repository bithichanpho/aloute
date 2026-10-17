package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.aloute.common.exception.TwoFactorRequiredException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.config.JwtAuthFilter;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.ForgotPasswordRequest;
import com.aloute.dto.request.FirebaseLoginRequest;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.request.ResendVerificationRequest;
import com.aloute.dto.request.ResetPasswordRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.AuthService;
import com.aloute.security.ClientIpResolver;
import com.aloute.service.FirebaseIdentityService;
import com.aloute.service.RefreshTokenService;
import com.aloute.model.entity.User;
import jakarta.servlet.http.Cookie;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirements
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private com.aloute.service.AccessTokenRevocationService accessRevocations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccessRevocations(com.aloute.service.AccessTokenRevocationService service) {
        this.accessRevocations = service;
    }
    private final JwtUtil jwtUtil;
    private final AuthRateLimitService authRateLimitService;
    private final FirebaseIdentityService firebaseIdentityService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    // Per-account (IP-independent) login failure budget. It stops distributed guessing that rotates
    // IPs against a single identifier. Kept well above the per-IP+account limit so that a few
    // attacker requests cannot easily lock a legitimate owner out.
    private static final String LOGIN_ACCOUNT_GLOBAL = "LOGIN_ACCOUNT_GLOBAL";

    @Value("${app.security.login.account-max-failures:30}")
    private int accountMaxFailures = 30;

    @Value("${app.security.login.account-window-minutes:15}")
    private long accountWindowMinutes = 15;

    @Value("${app.security.login.account-block-minutes:15}")
    private long accountBlockMinutes = 15;

    public AuthController(
            AuthService authService,
            JwtUtil jwtUtil,
            AuthRateLimitService authRateLimitService,
            FirebaseIdentityService firebaseIdentityService,
            ClientIpResolver clientIpResolver,
            RefreshTokenService refreshTokenService) {

        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.authRateLimitService = authRateLimitService;
        this.firebaseIdentityService = firebaseIdentityService;
        this.clientIpResolver = clientIpResolver;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {

        String ip = clientIpResolver.resolve(httpRequest);
        String registrationKey = ip + "|" + normalizeRateIdentifier(request.getEmail())
                + "|" + normalizeRateIdentifier(request.getUsername());
        authRateLimitService.checkAndRecord(
                "REGISTER_TARGET", registrationKey, 8,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "Bạn đã thử đăng ký tài khoản này quá nhiều lần. Thử lại sau khoảng %d phút.",
                "Yêu cầu đăng ký cho thông tin này tạm khóa 30 phút.");
        authRateLimitService.checkAndRecord(
                "REGISTER_IP", ip, 60,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "IP này đã gửi quá nhiều yêu cầu đăng ký. Thử lại sau khoảng %d phút.",
                "IP này tạm khóa đăng ký 30 phút do vượt giới hạn yêu cầu.");

        AuthResponse auth = authService.register(request);
        return ResponseEntity.ok(
                ApiResponse.success(
                        "Đã ghi nhận đăng ký. Vui lòng kiểm tra email (cả thư rác) để hoàn tất.", auth));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        String ip = clientIpResolver.resolve(httpRequest);
        String accountIdentifier = normalizeRateIdentifier(request.getUsername());
        String accountKey = ip + "|" + accountIdentifier;
        authRateLimitService.rejectIfBlocked(
                "LOGIN_ACCOUNT", accountKey,
                "Bạn đã đăng nhập sai tài khoản này quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.");
        authRateLimitService.rejectIfBlocked(
                LOGIN_ACCOUNT_GLOBAL, accountIdentifier,
                "Tài khoản này đang nhận quá nhiều lần đăng nhập sai. Vui lòng thử lại sau khoảng %d phút.");
        authRateLimitService.rejectIfBlocked(
                "LOGIN_IP", ip,
                "IP này đã gửi quá nhiều yêu cầu đăng nhập. Vui lòng thử lại sau khoảng %d phút.");

        try {
            AuthResponse auth = authService.login(request);
            authRateLimitService.reset("LOGIN_ACCOUNT", accountKey);
            authRateLimitService.reset(LOGIN_ACCOUNT_GLOBAL, accountIdentifier);
            return withAuthCookie(auth, "Đăng nhập thành công");
        } catch (TwoFactorRequiredException required) {
            throw required;
        } catch (UnauthorizedException failed) {
            authRateLimitService.checkAndRecord(
                    "LOGIN_ACCOUNT", accountKey, 10,
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(15),
                    "Bạn đã đăng nhập sai tài khoản này quá nhiều lần. Thử lại sau khoảng %d phút.",
                    "Đăng nhập cho tài khoản này tạm khóa 15 phút.");
            authRateLimitService.checkAndRecord(
                    LOGIN_ACCOUNT_GLOBAL, accountIdentifier, accountMaxFailures,
                    java.time.Duration.ofMinutes(accountWindowMinutes),
                    java.time.Duration.ofMinutes(accountBlockMinutes),
                    "Tài khoản này đang nhận quá nhiều lần đăng nhập sai. Thử lại sau khoảng %d phút.",
                    "Đăng nhập cho tài khoản này tạm khóa " + accountBlockMinutes + " phút do có quá nhiều lần thử sai.");
            authRateLimitService.checkAndRecord(
                    "LOGIN_IP", ip, 100,
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(15),
                    "IP này đã gửi quá nhiều yêu cầu đăng nhập. Thử lại sau khoảng %d phút.",
                    "IP này tạm khóa đăng nhập 15 phút do vượt giới hạn yêu cầu.");
            throw failed;
        }
    }

    @PostMapping("/firebase/login")
    public ResponseEntity<ApiResponse<AuthResponse>> firebaseLogin(
            @Valid @RequestBody FirebaseLoginRequest request, HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        authRateLimitService.rejectIfBlocked(
                "FIREBASE_LOGIN_IP",
                ip,
                "IP này đã gửi quá nhiều yêu cầu đăng nhập Google. Vui lòng thử lại sau khoảng %d phút.");
        try {
            AuthResponse auth = firebaseIdentityService.loginWithGoogle(request.getIdToken(), request.getCode());
            authRateLimitService.reset("FIREBASE_LOGIN_IP", ip);
            return withAuthCookie(auth, "Đăng nhập Google thành công");
        } catch (TwoFactorRequiredException required) {
            throw required; // not a failed attempt: don't count it against the IP limiter
        } catch (UnauthorizedException failed) {
            authRateLimitService.checkAndRecord(
                    "FIREBASE_LOGIN_IP",
                    ip,
                    60,
                    java.time.Duration.ofMinutes(15),
                    java.time.Duration.ofMinutes(15),
                    "IP này đã gửi quá nhiều yêu cầu đăng nhập Google. Vui lòng thử lại sau khoảng %d phút.",
                    "IP này tạm khóa đăng nhập Google 15 phút do vượt giới hạn yêu cầu.");
            throw failed;
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(HttpServletRequest request) {
        String rawRefresh = cookieValue(request, RefreshTokenService.COOKIE_NAME);
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefresh);
        User user = rotation.user();
        String access = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());
        AuthResponse body = new AuthResponse(access, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
        ResponseCookie accessCookie = accessCookie(access);
        ResponseCookie refreshCookie = refreshCookie(rotation.refreshToken());
        body.setAccessToken(null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success("Đã làm mới phiên đăng nhập", body));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String accessToken = null;
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7).trim();
        } else {
            accessToken = cookieValue(request, JwtAuthFilter.COOKIE_NAME);
        }
        if (accessToken != null && !accessToken.isBlank() && accessRevocations != null) {
            try { accessRevocations.revoke(accessToken, jwtUtil.expiresAt(accessToken)); }
            catch (io.jsonwebtoken.JwtException | IllegalArgumentException ignored) {
                // Invalid or expired tokens have no active session to revoke.
            }
        }
        refreshTokenService.revoke(cookieValue(request, RefreshTokenService.COOKIE_NAME));
        ResponseCookie expiredAccess = ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, "")
                .httpOnly(true).path("/").maxAge(0).sameSite("Lax").secure(cookieSecure).build();
        ResponseCookie expiredRefresh = ResponseCookie.from(RefreshTokenService.COOKIE_NAME, "")
                .httpOnly(true).path("/api/auth").maxAge(0).sameSite("Lax").secure(cookieSecure).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredAccess.toString())
                .header(HttpHeaders.SET_COOKIE, expiredRefresh.toString())
                .body(ApiResponse.success("Đã đăng xuất", null));
    }

    public record VerifyEmailRequest(
            @NotBlank(message = "Thiếu mã xác thực")
            @Size(max = 512, message = "Mã xác thực không hợp lệ") String token) {}

    @PostMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ApiResponse.success("Xác thực email thành công", null);
    }

    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest) {

        String ip = clientIpResolver.resolve(httpRequest);
        authRateLimitService.checkAndRecord(
                "RESEND_VERIFICATION_TARGET", ip + "|" + normalizeRateIdentifier(request.getIdentifier()),
                5, java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "Bạn đã yêu cầu gửi lại email quá nhiều lần. Thử lại sau khoảng %d phút.",
                "Yêu cầu xác minh cho định danh này tạm khóa 30 phút.");
        authRateLimitService.checkAndRecord(
                "RESEND_VERIFICATION_IP", ip, 50,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "IP này đã yêu cầu gửi email quá nhiều lần. Thử lại sau khoảng %d phút.",
                "IP này tạm khóa yêu cầu gửi email 30 phút.");

        authService.resendVerificationEmail(request.getIdentifier());

        return ApiResponse.success(
                "Nếu tài khoản tồn tại và chưa xác thực, email mới đã được gửi", null);
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {

        String ip = clientIpResolver.resolve(httpRequest);
        authRateLimitService.checkAndRecord(
                "FORGOT_PASSWORD_TARGET", ip + "|" + normalizeRateIdentifier(request.getEmail()),
                5, java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "Bạn đã yêu cầu đặt lại mật khẩu quá nhiều lần. Thử lại sau khoảng %d phút.",
                "Yêu cầu đặt lại mật khẩu cho địa chỉ này tạm khóa 30 phút.");
        authRateLimitService.checkAndRecord(
                "FORGOT_PASSWORD_IP", ip, 50,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
                "IP này đã yêu cầu đặt lại mật khẩu quá nhiều lần. Thử lại sau khoảng %d phút.",
                "IP này tạm khóa yêu cầu đặt lại mật khẩu 30 phút.");

        authService.forgotPassword(request.getEmail());

        return ApiResponse.success(
                "Nếu email tồn tại trong hệ thống, chúng tôi đã gửi link đặt lại mật khẩu", null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success("Đặt lại mật khẩu thành công, vui lòng đăng nhập lại", null);
    }

    private String normalizeRateIdentifier(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private ResponseEntity<ApiResponse<AuthResponse>> withAuthCookie(
            AuthResponse auth, String message) {
        String accessToken = auth.getAccessToken();
        String refreshToken = refreshTokenService.issue(auth.getUserId());
        ResponseCookie accessCookie = accessCookie(accessToken);
        ResponseCookie refreshCookie = refreshCookie(refreshToken);
        // Bearer credentials are carried only by HttpOnly cookies, never JSON.
        auth.setAccessToken(null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success(message, auth));
    }

    private ResponseCookie accessCookie(String token) {
        return ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, token)
                .httpOnly(true).path("/").maxAge(jwtUtil.getExpirationMs() / 1000)
                .sameSite("Lax").secure(cookieSecure).build();
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(RefreshTokenService.COOKIE_NAME, token)
                .httpOnly(true).path("/api/auth").maxAge(refreshTokenService.getLifetimeSeconds())
                .sameSite("Lax").secure(cookieSecure).build();
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
