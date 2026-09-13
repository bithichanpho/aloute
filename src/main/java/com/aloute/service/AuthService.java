package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.request.LoginRequest;
import com.aloute.dto.request.RegisterRequest;
import com.aloute.dto.response.AuthResponse;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IRoleRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private final IUserRepository userRepository;
    private final IRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    public AuthService(
            IUserRepository userRepository,
            IRoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            EmailService emailService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    /** Đăng ký tài khoản mới, gửi email xác thực, trả về JWT để đăng nhập luôn. */
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BadRequestException("Tên đăng nhập đã được sử dụng");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email đã được sử dụng");
        }

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new ResourceNotFoundException("Chưa seed role ROLE_USER"));

        User user = new User();
        user.setFullName(request.getFullName());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(userRole);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setVerificationToken(UUID.randomUUID().toString());

        User saved = userRepository.save(user);

        emailService.sendVerificationEmail(saved.getEmail(), saved.getFullName(), saved.getVerificationToken());

        String token = jwtUtil.generateToken(saved.getUsername(), saved.getId(), userRole.getName());
        return new AuthResponse(token, saved.getId(), saved.getUsername(), saved.getFullName(), saved.getAvatarUrl());
    }

    /** Đăng nhập bằng username hoặc email + mật khẩu, trả về JWT nếu đúng. */
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .or(() -> userRepository.findByEmail(request.getUsername()))
                .orElseThrow(() -> new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu");
        }
        if (!user.isActive()) {
            throw new UnauthorizedException("Tài khoản đã bị khóa");
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().getName());
        return new AuthResponse(token, user.getId(), user.getUsername(), user.getFullName(), user.getAvatarUrl());
    }

    /** Xác thực email theo token gửi qua mail. */
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new BadRequestException("Link xác thực không hợp lệ hoặc đã được dùng"));

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        userRepository.save(user);
    }

    /**
     * Quên mật khẩu: sinh token dùng 1 lần, hết hạn sau 30 phút, gửi qua email.
     * Luôn trả về thành công dù email có tồn tại hay không (tránh lộ email nào
     * đã đăng ký trong hệ thống — kỹ thuật chuẩn chống dò email).
     */
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setResetPasswordToken(token);
            user.setResetPasswordExpiry(LocalDateTime.now().plusMinutes(30));
            userRepository.save(user);
            emailService.sendResetPasswordEmail(user.getEmail(), user.getFullName(), token);
        });
    }

    /** Đặt lại mật khẩu bằng token nhận qua email quên mật khẩu. */
    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByResetPasswordToken(token)
                .orElseThrow(() -> new BadRequestException("Link đặt lại mật khẩu không hợp lệ hoặc đã được dùng"));

        if (user.getResetPasswordExpiry() == null || user.getResetPasswordExpiry().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Link đặt lại mật khẩu đã hết hạn, vui lòng yêu cầu lại");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setResetPasswordToken(null);
        user.setResetPasswordExpiry(null);
        userRepository.save(user);
    }

    /** Đổi mật khẩu khi đã đăng nhập — phải nhập đúng mật khẩu hiện tại. */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BadRequestException("Mật khẩu hiện tại không đúng");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BadRequestException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}