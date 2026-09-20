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

    /**
     * Trả về avatar của người dùng, hoặc ảnh mặc định nếu chưa có / rỗng.
     * Ảnh Cloudinary được resize + nén nhẹ (150x150, tự chọn định dạng theo trình duyệt)
     * ngay tại đây — vì đây là nơi DUY NHẤT mọi DTO đi qua để lấy avatar, sửa 1 chỗ
     * là toàn bộ trang (feed, profile, chat, gợi ý kết bạn...) đều nhẹ hơn.
     */
    public static String resolve(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return DEFAULT_AVATAR;
        }
        return CloudinaryUrlUtil.avatar(avatarUrl);
    }
}