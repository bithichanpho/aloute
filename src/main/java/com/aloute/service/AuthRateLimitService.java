package com.aloute.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.TooManyRequestsException;

@Service
public class AuthRateLimitService {

    private static final int MAX_REQUESTS = 5;

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final Duration BLOCK_DURATION = Duration.ofMinutes(30);

    private final Map<String, RateState> states = new ConcurrentHashMap<>();

    public void checkAndRecord(String ipAddress) {
        checkAndRecord(
                "AUTH_MAIL",
                ipAddress,
                MAX_REQUESTS,
                WINDOW,
                BLOCK_DURATION,
                "Bạn đã yêu cầu gửi email quá nhiều lần. Chức năng này tạm khóa khoảng %d phút.",
                "Bạn đã gửi yêu cầu quá nhiều lần. Chức năng gửi email đã bị tạm khóa 30 phút.");
    }

    public void checkAndRecord(
            String namespace,
            String subjectKey,
            int maxRequests,
            Duration window,
            Duration blockDuration,
            String blockedMsgFmt,
            String triggerMsg) {

        String subject = normalizeIp(subjectKey);
        String key = namespace + ":" + subject;

        RateState state =
                states.computeIfAbsent(key, ignored -> new RateState(LocalDateTime.now()));

        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();

            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {

                long seconds = Duration.between(now, state.blockedUntil).getSeconds();

                long minutes = Math.max(1, (seconds + 59) / 60);

                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes));
            }

            if (state.blockedUntil != null && !now.isBefore(state.blockedUntil)) {

                state.blockedUntil = null;
                state.windowStart = now;
                state.requestCount = 0;
            }

            if (now.isAfter(state.windowStart.plus(window))) {

                state.windowStart = now;
                state.requestCount = 0;
            }

            if (state.requestCount >= maxRequests) {

                state.blockedUntil = now.plus(blockDuration);
                state.expiresAt = state.blockedUntil;

                throw new TooManyRequestsException(triggerMsg);
            }

            state.requestCount++;
            LocalDateTime windowEnd = state.windowStart.plus(window);
            state.expiresAt =
                    state.blockedUntil != null && state.blockedUntil.isAfter(windowEnd)
                            ? state.blockedUntil
                            : windowEnd;
        }
    }

    public void rejectIfBlocked(String namespace, String subjectKey, String blockedMsgFmt) {
        String key = namespace + ":" + normalizeIp(subjectKey);
        RateState state = states.get(key);
        if (state == null) return;

        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {
                long seconds = Duration.between(now, state.blockedUntil).getSeconds();
                long minutes = Math.max(1, (seconds + 59) / 60);
                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes));
            }
        }
    }

    public void reset(String namespace, String subjectKey) {
        states.remove(namespace + ":" + normalizeIp(subjectKey));
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {

        LocalDateTime now = LocalDateTime.now();

        states.entrySet()
                .removeIf(
                        entry -> {
                            RateState state = entry.getValue();

                            synchronized (state) {
                                if (state.blockedUntil != null
                                        && now.isBefore(state.blockedUntil)) {
                                    return false;
                                }

                                return state.expiresAt == null || now.isAfter(state.expiresAt);
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

        private LocalDateTime expiresAt;

        private RateState(LocalDateTime windowStart) {
            this.windowStart = windowStart;
            this.expiresAt = windowStart;
        }
    }
}