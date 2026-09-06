package com.aloute.security;

import org.springframework.security.core.Authentication;

/** Tiện ích lấy userId của người đang đăng nhập từ SecurityContext, dùng trong mọi Controller. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

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
        if (authentication != null && authentication.getPrincipal() instanceof AloutePrincipal principal) {
            return principal;
        }
        return null;
    }
}