package com.aloute.repository;

import com.aloute.model.entity.User;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long> {

    @Override
    @EntityGraph(attributePaths = "role")
    Page<User> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "role")
    Page<User> findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String fullName, String email, Pageable pageable);

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

    /**
     * Atomically spends a password-reset OTP: it only succeeds if the row still holds exactly this
     * OTP hash and the code has not expired, and it clears the hash in the same statement.
     * Two concurrent requests with the same code therefore cannot both succeed (the loser sees 0).
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

    @Query("select count(u) from User u where u.active = false"
            + " and (u.selfDeactivated = false or u.selfDeactivated is null)")
    long countLockedAccounts();

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            LocalDateTime from, LocalDateTime to);

    @Query(value = """
            select cast(created_at as date) as day, count(*) as count
            from users
            where created_at >= :from and created_at < :to
            group by cast(created_at as date)
            order by day
            """, nativeQuery = true)
    List<DailyCountProjection> countCreatedByDay(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = """
            select count(*) as \"totalUsers\",
                   count(*) filter (where created_at >= :todayStart and created_at < :tomorrow)
                       as \"newUsersToday\",
                   count(*) filter (where is_active = false
                       and coalesce(self_deactivated, false) = false) as \"lockedAccounts\"
            from users
            """, nativeQuery = true)
    HostUserStatsProjection loadHostStats(
            @Param("todayStart") LocalDateTime todayStart,
            @Param("tomorrow") LocalDateTime tomorrow);

    @Query("select u.id from User u where u.active = false")
    @org.springframework.cache.annotation.Cacheable(cacheNames = "userStatus", key = "'inactiveIds'")
    List<Long> findInactiveIds();

    @Query("select u.id from User u where u.active = true and u.id in :ids")
    List<Long> findActiveIdsByIdIn(@Param("ids") java.util.Collection<Long> ids);
}
