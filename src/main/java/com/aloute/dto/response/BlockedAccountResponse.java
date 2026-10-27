package com.aloute.dto.response;

import java.time.Instant;

/** Existing block fields plus public account labels, without reconciliation state or credentials. */
public record BlockedAccountResponse(String id, Long ownerId, Long targetId, Instant createdAt,
        String targetUsername, String targetFullName, String targetAvatarUrl) {}
