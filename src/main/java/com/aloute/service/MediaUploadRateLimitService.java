package com.aloute.service;

import org.springframework.stereotype.Service;

import java.time.Duration;

/** One upload rate policy shared by generic upload routes and the direct avatar endpoint. */
@Service
public class MediaUploadRateLimitService {
    private static final int MAX_UPLOADS_PER_WINDOW = 30;
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration BLOCK = Duration.ofMinutes(15);

    private final AuthRateLimitService limits;

    public MediaUploadRateLimitService(AuthRateLimitService limits) {
        this.limits = limits;
    }

    public void assertAllowed(Long userId) {
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Cần đăng nhập để tải tệp lên");
        }
        limits.checkAndRecord(
                "UPLOAD_USER",
                String.valueOf(userId),
                MAX_UPLOADS_PER_WINDOW,
                WINDOW,
                BLOCK,
                "Bạn đã tải tệp quá nhiều lần. Vui lòng thử lại sau %d phút.",
                "Bạn đã vượt quá giới hạn tải tệp; vui lòng thử lại sau 15 phút.");
    }
}
