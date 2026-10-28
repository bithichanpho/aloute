package com.aloute.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;

public record AdminAccountSummary(
        Long id,
        String username,
        String email,
        String fullName,
        String role,
        boolean active,
        boolean selfDeactivated,
        boolean emailVerified,
        boolean deleted,
        Instant tokensRevokedAt,
        LocalDateTime createdAt) {}
