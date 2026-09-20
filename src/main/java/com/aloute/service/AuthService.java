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

	/**
	 * Link xác thực email có hiệu lực trong 15 phút.
	 */
	private static final long VERIFICATION_TOKEN_MINUTES = 15;

	/**
	 * PendingUser chưa verify sẽ được giữ tối đa 24 giờ.
	 */
	private static final long PENDING_RETENTION_HOURS = 24;

	/**
	 * Token reset password có hiệu lực trong 30 phút.
	 */
	private static final long RESET_PASSWORD_TOKEN_MINUTES = 30;

	/**
	 * Không gửi liên tục email reset password. Sau khi gửi phải chờ ít nhất 60
	 * giây.
	 */
	private static final long RESET_EMAIL_COOLDOWN_SECONDS = 60;

	private final IUserRepository userRepository;
	private final IPendingUserRepository pendingUserRepository;
	private final IRoleRepository roleRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;
	private final EmailService emailService;

	/**
	 * Service chuyên:
	 *
	 * 1. Sinh token bảo mật. 2. Hash token bằng SHA-256.
	 *
	 * Token thật gửi qua email. Database chỉ lưu hash.
	 */
	private final AuthTokenService authTokenService;

	public AuthService(IUserRepository userRepository, IPendingUserRepository pendingUserRepository,
			IRoleRepository roleRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService,
			AuthTokenService authTokenService) {

		this.userRepository = userRepository;
		this.pendingUserRepository = pendingUserRepository;
		this.roleRepository = roleRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtUtil = jwtUtil;
		this.emailService = emailService;
		this.authTokenService = authTokenService;
	}

	// =========================================================
	// REGISTER
	// =========================================================

	/**
	 * Đăng ký tài khoản.
	 *
	 * Chưa tạo User ngay.
	 *
	 * Quy trình:
	 *
	 * register ↓ PendingUser ↓ gửi email xác thực ↓ người dùng verify ↓ mới tạo
	 * User thật
	 *
	 * Vì vậy người chưa xác thực sẽ không nhận JWT.
	 */
	public AuthResponse register(RegisterRequest request) {

		String username = request.getUsername().trim();

		String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

		String fullName = request.getFullName().trim();

		// =====================================================
		// Kiểm tra User đã tồn tại
		// =====================================================

		if (userRepository.existsByUsername(username)) {
			throw new BadRequestException("Tên đăng nhập đã được sử dụng");
		}

		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new BadRequestException("Email đã được sử dụng");
		}

		LocalDateTime now = LocalDateTime.now();

		/*
		 * Kiểm tra xem username/email có PendingUser đang chờ xác thực hay không.
		 */
		removeExpiredPendingConflictsOrThrow(username, email, now);

		// =====================================================
		// Sinh verification token
		// =====================================================

		/*
		 * token thật:
		 *
		 * Ví dụ: fjH9Pz....ABC
		 *
		 * Token này CHỈ được gửi qua email.
		 */
		String verificationToken = authTokenService.generateToken();

		/*
		 * DB không lưu token thật.
		 *
		 * DB chỉ lưu:
		 *
		 * SHA256(token)
		 */
		String verificationTokenHash = authTokenService.hashToken(verificationToken);

		// =====================================================
		// Tạo PendingUser
		// =====================================================

		PendingUser pending = new PendingUser();

		pending.setFullName(fullName);
		pending.setUsername(username);
		pending.setEmail(email);

		/*
		 * Password vẫn dùng BCrypt như cũ.
		 */
		pending.setPassword(passwordEncoder.encode(request.getPassword()));

		/*
		 * QUAN TRỌNG:
		 *
		 * database lưu HASH, không lưu verificationToken thật.
		 */
		pending.setVerificationToken(verificationTokenHash);

		pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

		pending.setVerificationEmailSentAt(now);

		pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

		PendingUser saved;

		try {

			saved = pendingUserRepository.save(pending);

		} catch (DataIntegrityViolationException exception) {

			/*
			 * Trường hợp 2 request register trùng chạy gần như cùng lúc.
			 */
			throw new BadRequestException("Tên đăng nhập hoặc email đã được sử dụng");
		}

		// =====================================================
		// Gửi token THẬT qua email
		// =====================================================

		/*
		 * KHÔNG dùng:
		 *
		 * saved.getVerificationToken()
		 *
		 * vì nó bây giờ là HASH.
		 *
		 * Email phải nhận verificationToken thật.
		 */
		try {
			emailService.sendVerificationEmail(saved.getEmail(), saved.getFullName(), verificationToken);
		} catch (com.aloute.common.exception.EmailDeliveryException ex) {
			// SMTP thất bại: vẫn giữ PendingUser để token không bị mất, nhưng
			// không áp cooldown 60 giây cho lần gửi lại.
			saved.setVerificationEmailSentAt(now.minusMinutes(1));
			pendingUserRepository.save(saved);
			throw ex;
		}

		/*
		 * Chưa verify:
		 *
		 * userId = null JWT = null
		 */
		return new AuthResponse(null, null, saved.getUsername(), saved.getFullName(), null);
	}

	// =========================================================
	// PENDING USER CONFLICT
	// =========================================================

	private void removeExpiredPendingConflictsOrThrow(String username, String email, LocalDateTime now) {

		var byUsername = pendingUserRepository.findByUsername(username);

		var byEmail = pendingUserRepository.findByEmailIgnoreCase(email);

		/*
		 * Nếu PendingUser vẫn còn hạn 24 giờ thì không cho register lại.
		 *
		 * Người dùng phải dùng resend verification.
		 */
		boolean hasLiveConflict = byUsername.map(pending -> pending.getExpiresAt().isAfter(now)).orElse(false)

				||

				byEmail.map(pending -> pending.getExpiresAt().isAfter(now)).orElse(false);

		if (hasLiveConflict) {

			throw new BadRequestException("Đăng ký đang chờ xác thực. " + "Hãy dùng chức năng gửi lại email xác thực");
		}

		/*
		 * Pending đã hết hạn thì được phép xóa.
		 */
		byUsername.ifPresent(pendingUserRepository::delete);

		/*
		 * Tránh delete cùng một PendingUser hai lần.
		 */
		byEmail.filter(pending -> byUsername.isEmpty() || !pending.getId().equals(byUsername.get().getId()))
				.ifPresent(pendingUserRepository::delete);
	}

	// =========================================================
	// LOGIN
	// =========================================================

	/**
	 * Đăng nhập bằng:
	 *
	 * username hoặc email
	 *
	 * Chỉ user đã verify email mới nhận JWT.
	 */
	public AuthResponse login(LoginRequest request) {

		String identifier = request.getUsername().trim();

		var userOptional = userRepository.findByUsername(identifier)
				.or(() -> userRepository.findByEmailIgnoreCase(identifier));

		// =====================================================
		// Không tìm thấy User thật
		// =====================================================

		if (userOptional.isEmpty()) {

			/*
			 * Kiểm tra xem có phải tài khoản đang nằm ở PendingUser hay không.
			 */
			var pendingOptional = pendingUserRepository.findByUsername(identifier)
					.or(() -> pendingUserRepository.findByEmailIgnoreCase(identifier));

			/*
			 * Chỉ báo "chưa xác thực email" nếu mật khẩu nhập đúng.
			 *
			 * Nếu password sai vẫn báo chung:
			 *
			 * "Sai tên đăng nhập hoặc mật khẩu"
			 *
			 * để tránh lộ tài khoản tồn tại.
			 */
			if (pendingOptional.isPresent()
					&& passwordEncoder.matches(request.getPassword(), pendingOptional.get().getPassword())) {

				throw new UnauthorizedException("Vui lòng xác thực email " + "trước khi đăng nhập");
			}

			throw new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu");
		}

		User user = userOptional.get();

		// =====================================================
		// Check password
		// =====================================================

		if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {

			throw new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu");
		}

		// =====================================================
		// Check account active
		// =====================================================

		if (!user.isActive()) {

			throw new UnauthorizedException("Tài khoản đã bị khóa");
		}

		// =====================================================
		// Check email verified
		// =====================================================

		if (!user.isEmailVerified()) {

			throw new UnauthorizedException("Vui lòng xác thực email " + "trước khi đăng nhập");
		}

		// =====================================================
		// Generate JWT
		// =====================================================

		String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());

		return new AuthResponse(token, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
	}

	// =========================================================
	// VERIFY EMAIL
	// =========================================================

	/**
	 * Người dùng click link trong email.
	 *
	 * URL gửi token THẬT.
	 *
	 * Backend:
	 *
	 * token thật ↓ SHA-256 ↓ tìm hash trong database
	 */
	@Transactional
	public void verifyEmail(String token) {

		String value = token == null ? "" : token.trim();

		if (value.isBlank()) {

			throw new BadRequestException("Link xác thực không hợp lệ");
		}

		// =====================================================
		// Hash token nhận từ URL
		// =====================================================

		String tokenHash = authTokenService.hashToken(value);

		/*
		 * Với dữ liệu mới:
		 *
		 * DB chứa tokenHash.
		 */
		var pendingOptional = pendingUserRepository.findByVerificationToken(tokenHash);

		// =====================================================
		// Hỗ trợ token UUID cũ
		// =====================================================

		/*
		 * Trước khi thêm hash, project của bạn dùng UUID và lưu thẳng vào DB.
		 *
		 * Ví dụ:
		 *
		 * 550e8400-e29b-41d4-a716-446655440000
		 *
		 * Chỉ fallback query token thô nếu input thực sự có định dạng UUID cũ.
		 *
		 * KHÔNG query raw đối với token mới.
		 */
		if (pendingOptional.isEmpty() && isLegacyUuidToken(value)) {

			pendingOptional = pendingUserRepository.findByVerificationToken(value);
		}

		/*
		 * Không tìm thấy PendingUser thì thử User của phiên bản cũ.
		 */
		if (pendingOptional.isEmpty()) {

			verifyLegacyUser(value);
			return;
		}

		PendingUser pending = pendingOptional.get();

		// =====================================================
		// Check expiry
		// =====================================================

		if (pending.getVerificationTokenExpiry() == null
				|| !pending.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

			throw new BadRequestException("Link xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại email");
		}

		// =====================================================
		// Kiểm tra conflict lần cuối
		// =====================================================

		if (userRepository.existsByUsername(pending.getUsername())
				|| userRepository.existsByEmailIgnoreCase(pending.getEmail())) {

			throw new BadRequestException("Tên đăng nhập hoặc email đã được sử dụng");
		}

		// =====================================================
		// Get ROLE_USER
		// =====================================================

		Role userRole = roleRepository.findByName("ROLE_USER")
				.orElseThrow(() -> new ResourceNotFoundException("Chưa seed role ROLE_USER"));

		// =====================================================
		// Chuyển PendingUser → User
		// =====================================================

		User user = new User();

		user.setFullName(pending.getFullName());

		user.setUsername(pending.getUsername());

		user.setEmail(pending.getEmail());

		/*
		 * Password trong PendingUser đã là BCrypt hash, nên copy trực tiếp.
		 */
		user.setPassword(pending.getPassword());

		user.setRole(userRole);

		user.setActive(true);

		user.setEmailVerified(true);

		userRepository.save(user);

		/*
		 * Xóa PendingUser.
		 *
		 * Đồng nghĩa verification token cũng biến mất hoàn toàn.
		 *
		 * Link cũ không dùng lại được.
		 */
		pendingUserRepository.delete(pending);
	}

	// =========================================================
	// RESEND VERIFICATION
	// =========================================================

	/**
	 * Gửi lại email xác thực.
	 *
	 * Có thể nhập:
	 *
	 * username hoặc email
	 *
	 * Mỗi tài khoản phải chờ 60 giây.
	 */
	@Transactional
	public void resendVerificationEmail(String identifier) {

		String value = identifier == null ? "" : identifier.trim();

		var pendingOptional = pendingUserRepository.findByUsername(value)
				.or(() -> pendingUserRepository.findByEmailIgnoreCase(value));

		// =====================================================
		// Registration mới → PendingUser
		// =====================================================

		if (pendingOptional.isPresent()) {

			resendPendingVerification(pendingOptional.get());

			return;
		}

		// =====================================================
		// User cũ chưa verify
		// =====================================================

		/*
		 * Nhánh này chỉ để tương thích dữ liệu được tạo trước khi có PendingUser.
		 */
		var userOptional = userRepository.findByUsername(value).or(() -> userRepository.findByEmailIgnoreCase(value));

		/*
		 * Không tồn tại hoặc đã verify thì im lặng return.
		 *
		 * Tránh tiết lộ account tồn tại.
		 */
		if (userOptional.isEmpty() || userOptional.get().isEmailVerified()) {

			return;
		}

		User user = userOptional.get();

		LocalDateTime now = LocalDateTime.now();

		LocalDateTime lastSent = user.getVerificationEmailSentAt();

		// =====================================================
		// Cooldown 60 giây
		// =====================================================

		if (lastSent != null && now.isBefore(lastSent.plusMinutes(1))) {

			throw new BadRequestException("Vui lòng chờ 60 giây " + "trước khi gửi lại email");
		}

		// =====================================================
		// Sinh token mới
		// =====================================================

		String newToken = authTokenService.generateToken();

		/*
		 * Token mới ghi đè hash token cũ.
		 *
		 * Vì vậy email cũ tự động mất hiệu lực.
		 */
		user.setVerificationToken(authTokenService.hashToken(newToken));

		user.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

		user.setVerificationEmailSentAt(now);

		userRepository.save(user);

		// =====================================================
		// Mail nhận token THẬT
		// =====================================================

		emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), newToken);
	}

	// =========================================================
	// RESEND PENDING USER
	// =========================================================

	private void resendPendingVerification(PendingUser pending) {

		LocalDateTime now = LocalDateTime.now();

		// =====================================================
		// Pending registration đã hết hạn 24h
		// =====================================================

		if (!pending.getExpiresAt().isAfter(now)) {

			pendingUserRepository.delete(pending);

			throw new BadRequestException("Đăng ký đã hết hạn, " + "vui lòng đăng ký lại");
		}

		// =====================================================
		// Cooldown 60 giây
		// =====================================================

		LocalDateTime lastSent = pending.getVerificationEmailSentAt();

		if (lastSent != null && now.isBefore(lastSent.plusMinutes(1))) {

			throw new BadRequestException("Vui lòng chờ 60 giây " + "trước khi gửi lại email");
		}

		// =====================================================
		// Token mới
		// =====================================================

		String newToken = authTokenService.generateToken();

		/*
		 * DB chỉ lưu hash.
		 *
		 * Token cũ bị ghi đè, nên link email cũ không còn hiệu lực.
		 */
		pending.setVerificationToken(authTokenService.hashToken(newToken));

		pending.setVerificationTokenExpiry(now.plusMinutes(VERIFICATION_TOKEN_MINUTES));

		pending.setVerificationEmailSentAt(now);

		/*
		 * Gia hạn PendingUser thêm 24 giờ tính từ lần resend.
		 */
		pending.setExpiresAt(now.plusHours(PENDING_RETENTION_HOURS));

		pendingUserRepository.save(pending);

		// =====================================================
		// Email dùng token THẬT
		// =====================================================

		emailService.sendVerificationEmail(pending.getEmail(), pending.getFullName(), newToken);
	}

	// =========================================================
	// VERIFY LEGACY USER
	// =========================================================

	/**
	 * Dành cho các tài khoản cũ:
	 *
	 * trước đây registration tạo User luôn, chưa dùng PendingUser.
	 */
	private void verifyLegacyUser(String token) {

		if (token == null || token.isBlank()) {

			throw new BadRequestException("Link xác thực không hợp lệ " + "hoặc đã được dùng");
		}

		// =====================================================
		// Thử tìm theo HASH trước
		// =====================================================

		String tokenHash = authTokenService.hashToken(token);

		var userOptional = userRepository.findByVerificationToken(tokenHash);

		// =====================================================
		// Hỗ trợ dữ liệu UUID thô cũ
		// =====================================================

		/*
		 * Chỉ fallback raw nếu token nhập vào thực sự là UUID cũ.
		 *
		 * Điều này rất quan trọng.
		 *
		 * Nếu query raw mọi token thì người lấy được HASH trong database có thể dùng
		 * chính hash đó làm URL, khiến việc hash token mất ý nghĩa.
		 */
		if (userOptional.isEmpty() && isLegacyUuidToken(token)) {

			userOptional = userRepository.findByVerificationToken(token);
		}

		User user = userOptional
				.orElseThrow(() -> new BadRequestException("Link xác thực không hợp lệ " + "hoặc đã được dùng"));

		// =====================================================
		// Check expiry
		// =====================================================

		if (user.getVerificationTokenExpiry() == null
				|| !user.getVerificationTokenExpiry().isAfter(LocalDateTime.now())) {

			throw new BadRequestException("Link xác thực đã hết hạn, " + "vui lòng yêu cầu gửi lại email");
		}

		// =====================================================
		// Verify
		// =====================================================

		user.setEmailVerified(true);

		/*
		 * Token dùng xong bị xóa.
		 */
		user.setVerificationToken(null);

		user.setVerificationTokenExpiry(null);

		user.setVerificationEmailSentAt(null);

		userRepository.save(user);
	}

	// =========================================================
	// FORGOT PASSWORD
	// =========================================================

	/**
	 * Quên mật khẩu.
	 *
	 * Luôn trả về thành công kể cả email không tồn tại.
	 *
	 * Nhờ vậy attacker không dò được email nào đã đăng ký trong hệ thống.
	 */
	public void forgotPassword(String email) {

		String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

		if (normalizedEmail.isBlank()) {
			return;
		}

		userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {

			LocalDateTime now = LocalDateTime.now();

			// =========================================
			// Cooldown 60 giây / account
			// =========================================

			/*
			 * Hiện entity User chưa có field:
			 *
			 * resetPasswordEmailSentAt
			 *
			 * nên tạm suy ra thời điểm token được tạo từ expiry:
			 *
			 * expiry = createdAt + 30 phút
			 *
			 * createdAt = expiry - 30 phút
			 */
			if (user.getResetPasswordToken() != null && user.getResetPasswordExpiry() != null) {

				LocalDateTime tokenCreatedAt = user.getResetPasswordExpiry().minusMinutes(RESET_PASSWORD_TOKEN_MINUTES);

				if (now.isBefore(tokenCreatedAt.plusSeconds(RESET_EMAIL_COOLDOWN_SECONDS))) {

					/*
					 * Không throw lỗi ở đây.
					 *
					 * Controller vẫn trả thông báo chung "Nếu email tồn tại..."
					 *
					 * Nhờ vậy không lộ email tồn tại.
					 */
					return;
				}
			}

			// =========================================
			// Generate reset token
			// =========================================

			String resetToken = authTokenService.generateToken();

			/*
			 * Database chỉ lưu hash.
			 */
			String resetTokenHash = authTokenService.hashToken(resetToken);

			user.setResetPasswordToken(resetTokenHash);

			user.setResetPasswordExpiry(now.plusMinutes(RESET_PASSWORD_TOKEN_MINUTES));

			/*
			 * Ghi token mới sẽ tự động làm token cũ mất hiệu lực.
			 */
			userRepository.save(user);

			// =========================================
			// Email nhận token THẬT
			// =========================================

			emailService.sendResetPasswordEmail(user.getEmail(), user.getFullName(), resetToken);
		});
	}

	// =========================================================
	// RESET PASSWORD
	// =========================================================

	/**
	 * Đặt lại password bằng token nhận từ email.
	 */
	public void resetPassword(String token, String newPassword) {

		String value = token == null ? "" : token.trim();

		if (value.isBlank()) {

			throw new BadRequestException("Link đặt lại mật khẩu không hợp lệ " + "hoặc đã được dùng");
		}

		// =====================================================
		// Token URL → SHA256
		// =====================================================

		String tokenHash = authTokenService.hashToken(value);

		/*
		 * Token mới:
		 *
		 * query bằng hash.
		 */
		var userOptional = userRepository.findByResetPasswordToken(tokenHash);

		// =====================================================
		// Hỗ trợ reset token UUID cũ
		// =====================================================

		if (userOptional.isEmpty() && isLegacyUuidToken(value)) {

			userOptional = userRepository.findByResetPasswordToken(value);
		}

		User user = userOptional.orElseThrow(
				() -> new BadRequestException("Link đặt lại mật khẩu " + "không hợp lệ hoặc đã được dùng"));

		// =====================================================
		// Check expiry
		// =====================================================

		if (user.getResetPasswordExpiry() == null || user.getResetPasswordExpiry().isBefore(LocalDateTime.now())) {

			throw new BadRequestException("Link đặt lại mật khẩu " + "đã hết hạn, vui lòng yêu cầu lại");
		}

		// =====================================================
		// Set password mới
		// =====================================================

		user.setPassword(passwordEncoder.encode(newPassword));

		/*
		 * Dùng xong token bị vô hiệu ngay.
		 */
		user.setResetPasswordToken(null);

		user.setResetPasswordExpiry(null);

		userRepository.save(user);
	}

	// =========================================================
	// CHANGE PASSWORD
	// =========================================================

	/**
	 * Người đã login đổi password.
	 *
	 * Phải nhập đúng password hiện tại.
	 */
	public void changePassword(Long userId, String currentPassword, String newPassword) {

		User user = userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

		// =====================================================
		// Password hiện tại sai
		// =====================================================

		if (!passwordEncoder.matches(currentPassword, user.getPassword())) {

			throw new BadRequestException("Mật khẩu hiện tại không đúng");
		}

		// =====================================================
		// Không cho password mới giống password cũ
		// =====================================================

		if (passwordEncoder.matches(newPassword, user.getPassword())) {

			throw new BadRequestException("Mật khẩu mới phải khác " + "mật khẩu hiện tại");
		}

		user.setPassword(passwordEncoder.encode(newPassword));

		userRepository.save(user);
	}

	// =========================================================
	// LEGACY TOKEN HELPER
	// =========================================================

	/**
	 * Project cũ dùng:
	 *
	 * UUID.randomUUID().toString()
	 *
	 * Ví dụ:
	 *
	 * 550e8400-e29b-41d4-a716-446655440000
	 *
	 * Hàm này giúp nhận biết token cũ.
	 *
	 * Mục đích: các email được gửi trước khi update code vẫn có thể sử dụng trong
	 * thời gian token còn hạn.
	 */
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