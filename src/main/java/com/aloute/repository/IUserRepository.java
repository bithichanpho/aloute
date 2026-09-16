package com.aloute.repository;

import com.aloute.model.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long> {

    /**
     * Nạp sẵn "role" ngay trong câu lệnh gốc. Không có EntityGraph thì mỗi User
     * trong danh sách (feed, chat, tìm kiếm...) sẽ sinh thêm 1 truy vấn lấy role.
     */
    @Override
    @EntityGraph(attributePaths = "role")
    List<User> findAllById(Iterable<Long> ids);

    Optional<User> findByUsername(String username);

    /**
     * Email luôn đối chiếu không phân biệt hoa thường: "A@x.com" và "a@x.com"
     * phải được coi là cùng một tài khoản, nếu không người dùng có thể đăng ký trùng.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByVerificationToken(String verificationToken);

    Optional<User> findByResetPasswordToken(String resetPasswordToken);

    List<User> findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCase(String username, String fullName);

    /**
     * Pool ứng viên cho phần gợi ý. Trước đây phần gợi ý nạp TOÀN BỘ bảng users rồi mới
     * lọc xuống 6 người — với dữ liệu lớn dần đây là nguyên nhân chính làm trang feed chậm.
     */
    List<User> findTop60ByOrderByIdDesc();
}
