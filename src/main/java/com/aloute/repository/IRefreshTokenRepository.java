package com.aloute.repository;

import com.aloute.model.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IRefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(Long userId);

    /** Atomic compare-and-set prevents two concurrent refresh requests from both rotating one token. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null and t.expiresAt > :now")
    int revokeIfActive(@Param("id") Long id, @Param("now") Instant now);

    long deleteByExpiresAtBefore(Instant cutoff);
}
