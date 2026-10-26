package com.aloute.dto.response;

import java.time.Instant;

public record AdminAccountAuditResponse(
        Long id, Long userId, Long actorId, String action, String reason, Instant createdAt) {}
