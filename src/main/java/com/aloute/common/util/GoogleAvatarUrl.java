package com.aloute.common.util;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Exact Google profile-image origins only; never a general-purpose URL proxy. */
public final class GoogleAvatarUrl {
    private GoogleAvatarUrl() {}

    public static boolean isSupported(String value) {
        if (value == null || value.length() > 1024) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().toLowerCase(Locale.ROOT).matches("lh[1-6]\\.googleusercontent\\.com")
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null && uri.getFragment() == null
                    && uri.getPath() != null
                    && (uri.getPath().startsWith("/a/") || uri.getPath().startsWith("/a-/"))
                    && uri.normalize().getPath().equals(uri.getPath());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public static String localUrl(String value) {
        return "/images/avatars/google?url=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
