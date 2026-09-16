package com.aloute.common.util;

/**
 * Nơi duy nhất quyết định ảnh đại diện mặc định của hệ thống.
 *
 * Tài khoản chưa từng đổi avatar sẽ có avatarUrl = null trong DB. Thay vì để mỗi
 * template/JS tự bịa ra một ảnh placeholder khác nhau (trước đây mỗi trang dùng
 * một ảnh i.pravatar khác nhau), mọi DTO trả về cho client đều đi qua đây để lấy
 * cùng một ảnh nằm trong static/images/avatar_default.png.
 */
public final class AvatarUtil {

    public static final String DEFAULT_AVATAR = "/images/avatar_default.png";

    private AvatarUtil() {
    }

    /** Trả về avatar của người dùng, hoặc ảnh mặc định nếu chưa có / rỗng. */
    public static String resolve(String avatarUrl) {
        return (avatarUrl == null || avatarUrl.isBlank()) ? DEFAULT_AVATAR : avatarUrl;
    }
}
