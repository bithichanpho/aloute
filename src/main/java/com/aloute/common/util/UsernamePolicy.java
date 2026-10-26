package com.aloute.common.util;

import java.util.Locale;

/**
 * Single source of truth for usernames that the application reserves for itself.
 *
 * <p>When an account is deleted its username is replaced by a placeholder so the original name
 * becomes available again and personal data is not retained. That placeholder must never be able
 * to collide with a name a real person can register, otherwise the unique index
 * {@code uq_users_username_lower} makes the deletion transaction fail with a 500.
 */
public final class UsernamePolicy {

    /**
     * Placeholder prefix used by the old deletion code ({@code deleted_<id>}). Rows written with
     * it may still exist, and it matches the registration pattern, so new sign-ups must not use it.
     */
    public static final String LEGACY_DELETED_PREFIX = "deleted_";

    /**
     * Prefix of the current placeholder. {@code ~} is outside the registration pattern
     * {@code [A-Za-z0-9_.-]}, so no registered or Google-generated username can ever equal it.
     */
    public static final String DELETED_PREFIX = "deleted~";

    private UsernamePolicy() {}

    /** Username stored on a deleted account; unique per user id and impossible to register. */
    public static String deletedAccountUsername(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        return DELETED_PREFIX + userId;
    }

    /** True when {@code username} is (case-insensitively) in the namespace reserved for deleted accounts. */
    public static boolean isReserved(String username) {
        if (username == null) return false;
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(LEGACY_DELETED_PREFIX) || normalized.startsWith(DELETED_PREFIX);
    }
}
