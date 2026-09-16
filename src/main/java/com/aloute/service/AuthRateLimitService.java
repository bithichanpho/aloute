package com.aloute.service;

import com.aloute.common.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {

    // Cho phép tối đa 5 request trong 15 phút
    private static final int MAX_REQUESTS = 5;

    private static final Duration WINDOW =
            Duration.ofMinutes(15);

    // Spam quá mức → khóa chức năng gửi mail 30 phút
    private static final Duration BLOCK_DURATION =
            Duration.ofMinutes(30);

    private final Map<String, RateState> states =
            new ConcurrentHashMap<>();

    /**
     * Hai chức năng forgot-password và resend-verification
     * dùng chung quota chống spam.
     */
    public void checkAndRecord(String ipAddress) {

        String ip = normalizeIp(ipAddress);
        String key = "AUTH_MAIL:" + ip;

        RateState state = states.computeIfAbsent(
                key,
                ignored -> new RateState(LocalDateTime.now())
        );

        synchronized (state) {

            LocalDateTime now = LocalDateTime.now();

            // IP đang bị khóa
            if (state.blockedUntil != null
                    && now.isBefore(state.blockedUntil)) {

                long seconds = Duration.between(
                        now,
                        state.blockedUntil
                ).getSeconds();

                long minutes =
                        Math.max(1, (seconds + 59) / 60);

                throw new TooManyRequestsException(
                        "Bạn đã yêu cầu gửi email quá nhiều lần. "
                        + "Chức năng này tạm khóa khoảng "
                        + minutes
                        + " phút."
                );
            }

            // Hết thời gian khóa → reset
            if (state.blockedUntil != null
                    && !now.isBefore(state.blockedUntil)) {

                state.blockedUntil = null;
                state.windowStart = now;
                state.requestCount = 0;
            }

            // Sang cửa sổ 15 phút mới
            if (now.isAfter(
                    state.windowStart.plus(WINDOW))) {

                state.windowStart = now;
                state.requestCount = 0;
            }

            /*
             * Đã dùng đủ 5 lần.
             * Request tiếp theo sẽ kích hoạt khóa.
             */
            if (state.requestCount >= MAX_REQUESTS) {

                state.blockedUntil =
                        now.plus(BLOCK_DURATION);

                throw new TooManyRequestsException(
                        "Bạn đã gửi yêu cầu quá nhiều lần. "
                        + "Chức năng gửi email đã bị tạm khóa 30 phút."
                );
            }

            state.requestCount++;
        }
    }

    /**
     * Dọn IP cũ để map không tăng mãi.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {

        LocalDateTime now = LocalDateTime.now();

        states.entrySet().removeIf(entry -> {

            RateState state = entry.getValue();

            synchronized (state) {

                if (state.blockedUntil != null
                        && now.isBefore(state.blockedUntil)) {
                    return false;
                }

                return now.isAfter(
                        state.windowStart
                                .plus(WINDOW)
                                .plus(BLOCK_DURATION)
                );
            }
        });
    }

    private String normalizeIp(String ip) {

        if (ip == null || ip.isBlank()) {
            return "unknown";
        }

        return ip.trim();
    }

    private static class RateState {

        private LocalDateTime windowStart;

        private int requestCount;

        private LocalDateTime blockedUntil;

        private RateState(LocalDateTime windowStart) {
            this.windowStart = windowStart;
        }
    }
}