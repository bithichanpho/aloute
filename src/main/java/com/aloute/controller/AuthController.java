package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import com.aloute.config.JwtAuthFilter;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.ForgotPasswordRequest;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.request.ResendVerificationRequest;
import com.aloute.dto.request.ResetPasswordRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final AuthRateLimitService authRateLimitService;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    public AuthController(
            AuthService authService, JwtUtil jwtUtil, AuthRateLimitService authRateLimitService) {

        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.authRateLimitService = authRateLimitService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {

        authRateLimitService.checkAndRecord(
                "REGISTER",
                httpRequest.getRemoteAddr(),
                8,
                java.time.Duration.ofMinutes(15),
                java.time.Duration.ofMinutes(30),
                "Bạn đã đăng ký quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                "Bạn đã thử đăng ký quá nhiều lần. Chức năng đăng ký từ IP này tạm khóa 30 phút.");

        AuthResponse auth = authService.register(request);
        return ResponseEntity.ok(
                ApiResponse.success(
                        "Đăng ký thành công, vui lòng kiểm tra email để xác thực tài khoản", auth));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        String ip = httpRequest.getRemoteAddr();
        authRateLimitService.rejectIfBlocked(
                "LOGIN",
                ip,
                "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.");

        try {
            AuthResponse auth = authService.login(request);
            authRateLimitService.reset("LOGIN", ip);
            return withAuthCookie(auth, "Đăng nhập thành công");
        } catch (com.aloute.common.exception.TwoFactorRequiredException required) {
            throw required;
        } catch (com.aloute.common.exception.UnauthorizedException failed) {
            authRateLimitService.checkAndRecord(
                    "LOGIN",
                    ip,
                    10,
                    java.time.Duration.ofMinutes(15),
                    java.time.Duration.ofMinutes(15),
                    "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
                    "Bạn đã đăng nhập sai quá nhiều lần. Chức năng đăng nhập từ IP này tạm khóa 15"
                            + " phút.");
            throw failed;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        ResponseCookie expired =
                ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, "")
                        .httpOnly(true)
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .secure(cookieSecure)
                        .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(ApiResponse.success("Đã đăng xuất", null));
    }

    @GetMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(
            @RequestParam @NotBlank(message = "Thiếu mã xác thực")
                    @Size(max = 512, message = "Mã xác thực không hợp lệ") String token) {
        authService.verifyEmail(token);
        return ApiResponse.success("Xác thực email thành công", null);
    }

    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest) {

        authRateLimitService.checkAndRecord(httpRequest.getRemoteAddr());

        authService.resendVerificationEmail(request.getIdentifier());

        return ApiResponse.success(
                "Nếu tài khoản tồn tại và chưa xác thực, email mới đã được gửi", null);
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {

        authRateLimitService.checkAndRecord(httpRequest.getRemoteAddr());

        authService.forgotPassword(request.getEmail());

        return ApiResponse.success(
                "Nếu email tồn tại trong hệ thống, chúng tôi đã gửi link đặt lại mật khẩu", null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success("Đặt lại mật khẩu thành công, vui lòng đăng nhập lại", null);
    }

    private ResponseEntity<ApiResponse<AuthResponse>> withAuthCookie(
            AuthResponse auth, String message) {
        ResponseCookie cookie =
                ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, auth.getAccessToken())
                        .httpOnly(true)
                        .path("/")
                        .maxAge(jwtUtil.getExpirationMs() / 1000)
                        .sameSite("Lax")
                        .secure(cookieSecure)
                        .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(message, auth));
    }
}
