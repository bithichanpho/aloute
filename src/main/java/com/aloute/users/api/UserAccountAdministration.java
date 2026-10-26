package com.aloute.users.api;

import java.time.Instant;

/** Users owns account state; every administrative command checks the authoritative actor role. */
public interface UserAccountAdministration {
    record ChangedAccount(Long id, String username, Instant revokedAt) {}
    record AccountReview(String username, String fullName, boolean active) {}

    void requireAdministrator(Long actorId);
    AccountReview reviewAccount(Long actorId, Long targetId);
    ChangedAccount setHiddenByAdministrator(Long actorId, Long targetId, boolean hidden);
}
