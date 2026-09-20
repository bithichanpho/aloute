package com.aloute.common.util;

/**
 * Chèn Cloudinary transformation vào URL ảnh đã lưu (on-the-fly, không cần upload lại,
 * không cần đổi dữ liệu trong DB — ảnh vẫn lưu URL gốc, transformation chỉ áp lúc trả về client).
 *
 * Vấn đề trước khi có class này: {@link ImageStorageService} lưu thẳng "secure_url" Cloudinary
 * trả về lúc upload — tức ảnh gốc, đúng kích thước người dùng chọn (có thể vài chục MB).
 * Avatar hiển thị 40x40px trên UI vẫn tải nguyên file gốc 5000x5000px về trình duyệt.
 * Đây là nguyên nhân lớn khiến /feed, /profile tải chậm khi có nhiều ảnh.
 *
 * Cloudinary cho phép chèn 1 đoạn transformation ngay trong URL, giữa "/upload/" và phần còn lại,
 * server Cloudinary sẽ tự resize + nén + chọn định dạng (WebP/AVIF cho trình duyệt hỗ trợ) và
 * cache lại bản đã xử lý — không tốn thêm quota upload, không cần đổi service lưu ảnh.
 */
public final class CloudinaryUrlUtil {

    private static final String UPLOAD_MARKER = "/upload/";

    private CloudinaryUrlUtil() {
    }

    /** Avatar: vuông 150x150, focus vào khuôn mặt nếu phát hiện được. */
    public static String avatar(String url) {
        return transform(url, "c_fill,g_face,w_150,h_150,q_auto,f_auto");
    }

    /** Ảnh bìa trang cá nhân: rộng, tỉ lệ cố định. */
    public static String cover(String url) {
        return transform(url, "c_fill,w_1200,h_400,q_auto,f_auto");
    }

    /** Ảnh trong bài viết/feed: giới hạn chiều rộng hiển thị thực tế (không phóng to nếu ảnh gốc nhỏ hơn). */
    public static String feedImage(String url) {
        return transform(url, "c_limit,w_1080,q_auto,f_auto");
    }

    /**
     * @param url            URL gốc lưu trong DB.
     * @param transformation chuỗi transformation Cloudinary, vd "c_fill,w_150,h_150,q_auto,f_auto".
     * @return URL đã chèn transformation, hoặc URL gốc nếu không phải ảnh Cloudinary
     *         (vd ảnh mặc định /images/avatar_default.png nằm trong static resources).
     */
    private static String transform(String url, String transformation) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int idx = url.indexOf(UPLOAD_MARKER);
        if (idx < 0) {
            // Không phải URL Cloudinary (vd ảnh mặc định local) -> giữ nguyên, không đụng vào.
            return url;
        }
        int insertAt = idx + UPLOAD_MARKER.length();
        String rest = url.substring(insertAt);
        if (rest.startsWith(transformation)) {
            // Đã áp transformation này rồi (DTO có thể được dựng lại nhiều lần trong 1 request)
            // -> tránh chèn lặp lại thành chuỗi transformation lồng nhau vô nghĩa.
            return url;
        }
        return url.substring(0, insertAt) + transformation + "/" + rest;
    }
}