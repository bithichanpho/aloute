package com.aloute.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.response.ApiResponse;
import com.aloute.config.JwtAuthFilter;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.ForgotPasswordRequest;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.request.ResetPasswordRequest;
import com.aloute.dto.request.ResendVerificationRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.service.AuthService;
import com.aloute.service.AuthRateLimitService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;
	private final JwtUtil jwtUtil;
	private final AuthRateLimitService authRateLimitService;

	@Value("${app.security.cookie-secure:false}")
	private boolean cookieSecure;

	public AuthController(AuthService authService, JwtUtil jwtUtil, AuthRateLimitService authRateLimitService) {

		this.authService = authService;
		this.jwtUtil = jwtUtil;
		this.authRateLimitService = authRateLimitService;
	}

	@PostMapping("/register")
	public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest httpRequest) {

		// Chặn tạo tài khoản hàng loạt (bot) từ cùng 1 IP: tối đa 8 lần / 15 phút,
		// vượt quá thì khóa chức năng đăng ký từ IP đó trong 30 phút.
		authRateLimitService.checkAndRecord("REGISTER", httpRequest.getRemoteAddr(),
				8, java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30),
				"Bạn đã đăng ký quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
				"Bạn đã thử đăng ký quá nhiều lần. Chức năng đăng ký từ IP này tạm khóa 30 phút.");

		AuthResponse auth = authService.register(request);
		return ResponseEntity
				.ok(ApiResponse.success("Đăng ký thành công, vui lòng kiểm tra email để xác thực tài khoản", auth));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest httpRequest) {

		// Chống brute-force đoán mật khẩu: tối đa 10 lần đăng nhập SAI liên tiếp / 15 phút
		// từ cùng 1 IP thì khóa chức năng đăng nhập từ IP đó 15 phút. Chỉ tính request
		// login THẤT BẠI (sai mật khẩu/tài khoản) — đăng nhập đúng ngay từ đầu không bị
		// tính vào quota này, tránh làm phiền người dùng hợp lệ dùng chung mạng (NAT/wifi công cộng).
		String ip = httpRequest.getRemoteAddr();
		authRateLimitService.rejectIfBlocked("LOGIN", ip,
				"Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.");

		try {
			AuthResponse auth = authService.login(request);
			authRateLimitService.reset("LOGIN", ip);
			return withAuthCookie(auth, "Đăng nhập thành công");
		} catch (com.aloute.common.exception.UnauthorizedException failed) {
			authRateLimitService.checkAndRecord("LOGIN", ip,
					10, java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(15),
					"Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau khoảng %d phút.",
					"Bạn đã đăng nhập sai quá nhiều lần. Chức năng đăng nhập từ IP này tạm khóa 15 phút.");
			throw failed;
		}
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout() {
		ResponseCookie expired = ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, "").httpOnly(true).path("/").maxAge(0)
				.sameSite("Lax").secure(cookieSecure).build();

		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expired.toString())
				.body(ApiResponse.success("Đã đăng xuất", null));
	}

	@GetMapping("/verify-email")
	public ApiResponse<Void> verifyEmail(@RequestParam String token) {
		authService.verifyEmail(token);
		return ApiResponse.success("Xác thực email thành công", null);
	}

	@PostMapping("/resend-verification")
	public ApiResponse<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
			HttpServletRequest httpRequest) {

		authRateLimitService.checkAndRecord(httpRequest.getRemoteAddr());

		authService.resendVerificationEmail(request.getIdentifier());

		return ApiResponse.success("Nếu tài khoản tồn tại và chưa xác thực, email mới đã được gửi", null);
	}

	/**
	 * Gửi email chứa link đặt lại mật khẩu nếu email tồn tại (luôn trả về thành
	 * công).
	 */
	@PostMapping("/forgot-password")
	public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
			HttpServletRequest httpRequest) {

		authRateLimitService.checkAndRecord(httpRequest.getRemoteAddr());

		authService.forgotPassword(request.getEmail());

		return ApiResponse.success("Nếu email tồn tại trong hệ thống, chúng tôi đã gửi link đặt lại mật khẩu", null);
	}

	/** Đặt mật khẩu mới bằng token nhận được từ email quên mật khẩu. */
	@PostMapping("/reset-password")
	public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		authService.resetPassword(request.getToken(), request.getNewPassword());
		return ApiResponse.success("Đặt lại mật khẩu thành công, vui lòng đăng nhập lại", null);
	}

	/**
	 * Gắn JWT vào cả body (để app.js lưu tạm nếu cần) lẫn cookie HttpOnly (để mọi
	 * request tiếp theo — kể cả tải lại trang HTML hay bắt tay WebSocket — tự động
	 * mang theo, không cần JS set thủ công header Authorization).
	 */
	private ResponseEntity<ApiResponse<AuthResponse>> withAuthCookie(AuthResponse auth, String message) {
		ResponseCookie cookie = ResponseCookie.from(JwtAuthFilter.COOKIE_NAME, auth.getAccessToken()).httpOnly(true)
				.path("/").maxAge(jwtUtil.getExpirationMs() / 1000).sameSite("Lax").secure(cookieSecure).build();

		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(ApiResponse.success(message, auth));
	}
}