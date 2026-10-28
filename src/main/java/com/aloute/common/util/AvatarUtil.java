package com.aloute.common.util;

public final class AvatarUtil {

    public static final String DEFAULT_AVATAR = "/images/avatar_default.png";

    private AvatarUtil() {}

    public static String resolve(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return DEFAULT_AVATAR;
        }
        if (GoogleAvatarUrl.isSupported(avatarUrl)) return GoogleAvatarUrl.localUrl(avatarUrl);
        return CloudinaryUrlUtil.avatar(avatarUrl);
    }
}
