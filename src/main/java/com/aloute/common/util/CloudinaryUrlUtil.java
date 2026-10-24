package com.aloute.common.util;

public final class CloudinaryUrlUtil {

    private static final String UPLOAD_MARKER = "/upload/";

    private CloudinaryUrlUtil() {}

    public static String avatar(String url) {
        return transform(url, "c_fill,g_face,w_150,h_150,q_auto,f_auto");
    }

    public static String cover(String url) {
        return transform(url, "c_fill,w_1200,h_400,q_auto,f_auto");
    }

    public static String feedImage(String url) {
        return transform(url, "c_limit,w_1080,q_auto,f_auto");
    }

    private static String transform(String url, String transformation) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int idx = url.indexOf(UPLOAD_MARKER);
        if (idx < 0) {

            return url;
        }
        int insertAt = idx + UPLOAD_MARKER.length();
        String rest = url.substring(insertAt);
        if (rest.startsWith(transformation)) {

            return url;
        }
        return url.substring(0, insertAt) + transformation + "/" + rest;
    }
}
