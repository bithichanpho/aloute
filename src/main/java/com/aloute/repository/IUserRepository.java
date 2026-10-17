package com.aloute.repository;

import com.aloute.model.entity.User;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long> {

    @Override
    @EntityGraph(attributePaths = "role")
    List<User> findAllById(Iterable<Long> ids);

    Optional<User> findByUsername(String username);

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByAvatarUrl(String avatarUrl);

    boolean existsByCoverUrl(String coverUrl);

    Optional<User> findByVerificationToken(String verificationToken);

    Optional<User> findByResetPasswordToken(String resetPasswordToken);

    /**
     * Atomically spends a password-reset token: it only succeeds if the row still holds exactly this
     * token hash and the token has not expired, and it clears the token in the same statement.
     * Two concurrent requests with the same link therefore cannot both succeed (the loser sees 0).
     *
     * @return 1 if the token was spent and the password changed, 0 if it was already used,
     *     replaced or expired
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update User u set u.password = :password, u.resetPasswordToken = null,"
                    + " u.resetPasswordExpiry = null, u.tokensRevokedAt = :revokedAt,"
                    + " u.updatedAt = :now"
                    + " where u.id = :id and u.resetPasswordToken = :tokenHash"
                    + " and u.resetPasswordExpiry > :now")
    int consumeResetToken(
            @Param("id") Long id,
            @Param("tokenHash") String tokenHash,
            @Param("password") String password,
            @Param("revokedAt") Instant revokedAt,
            @Param("now") LocalDateTime now);

    List<User> findTop60ByOrderByIdDesc();

    List<User> findByActiveFalse();
}
