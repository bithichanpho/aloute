package com.aloute.repository;

import com.aloute.model.entity.PendingUser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IPendingUserRepository extends JpaRepository<PendingUser, Long> {

    Optional<PendingUser> findByUsername(String username);

    Optional<PendingUser> findByEmailIgnoreCase(String email);

    Optional<PendingUser> findByVerificationToken(String verificationToken);

    boolean existsByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);
}
