package com.aloute.security;

import org.springframework.security.core.Authentication;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AloutePrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    public static AloutePrincipal currentPrincipal(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AloutePrincipal principal) {
            return principal;
        }
        return null;
    }
}
