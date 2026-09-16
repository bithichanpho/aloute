package com.aloute.repository;

import com.aloute.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
    
    Optional<User> findByVerificationToken(String verificationToken);

    Optional<User> findByResetPasswordToken(String resetPasswordToken);
    
    List<User> findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCase(String username, String fullName);

    /**
     * Pool ứng viên cho phần gợi ý. Trước đây phần gợi ý nạp TOÀN BỘ bảng users rồi mới
     * lọc xuống 6 người — với dữ liệu lớn dần đây là nguyên nhân chính làm trang feed chậm.
     */
    List<User> findTop60ByOrderByIdDesc();
}