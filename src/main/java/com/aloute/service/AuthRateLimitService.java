package com.aloute.service;

import com.aloute.common.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Redis-backed rate limiting with an in-memory development fallback. */
@Service
public class AuthRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitService.class);
    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(30);

    // Atomic across app instances: count a request and set a block in one Redis script.
    private static final DefaultRedisScript<List<Long>> RECORD_SCRIPT = new DefaultRedisScript<>(
            "local ttl = redis.call('TTL', KEYS[2]); " +
            "if redis.call('EXISTS', KEYS[2]) == 1 then return {-1, math.max(ttl, 1)} end; " +
            "local count = redis.call('INCR', KEYS[1]); " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) end; " +
            "if count > tonumber(ARGV[1]) then " +
            " redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3])); " +
            " return {-2, tonumber(ARGV[3])}; " +
            "end; " +
            "return {count, redis.call('TTL', KEYS[1])};", longListType());

    @SuppressWarnings("unchecked")
    private static Class<List<Long>> longListType() {
        return (Class<List<Long>>) (Class<?>) List.class;
    }

    private final StringRedisTemplate redis;
    private final boolean redisRequired;
    private final Map<String, RateState> fallbackStates = new ConcurrentHashMap<>();

    public AuthRateLimitService(
            StringRedisTemplate redis,
            @Value("${app.security.rate-limit.redis-required:false}") boolean redisRequired) {
        this.redis = redis;
        this.redisRequired = redisRequired;
    }

    public void checkAndRecord(String subjectKey) {
        checkAndRecord(
                "AUTH_MAIL", subjectKey, MAX_REQUESTS, WINDOW, BLOCK_DURATION,
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
        // Preserve any temporary block created during a previous Redis outage on this instance.
        localRejectIfBlocked(namespace, subjectKey, blockedMsgFmt);
        String key = key(namespace, subjectKey);
        try {
            List<Long> result = redis.execute(
                    RECORD_SCRIPT,
                    List.of(key + ":count", key + ":blocked"),
                    String.valueOf(maxRequests),
                    String.valueOf(seconds(window)),
                    String.valueOf(seconds(blockDuration)));
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Redis rate-limit script returned an invalid result");
            }
            long status = number(result.get(0));
            long ttl = Math.max(1, number(result.get(1)));
            if (status == -1) {
                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes(ttl)));
            }
            if (status == -2) {
                throw new TooManyRequestsException(triggerMsg);
            }
        } catch (TooManyRequestsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (redisRequired) throw redisUnavailable(ex);
            warnRedisFallback(ex);
            localCheckAndRecord(namespace, subjectKey, maxRequests, window, blockDuration,
                    blockedMsgFmt, triggerMsg);
        }
    }

    public void rejectIfBlocked(String namespace, String subjectKey, String blockedMsgFmt) {
        // Preserve a block created during a Redis outage even if Redis becomes available again.
        localRejectIfBlocked(namespace, subjectKey, blockedMsgFmt);
        String key = key(namespace, subjectKey);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key + ":blocked"))) {
                Long ttl = redis.getExpire(key + ":blocked");
                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes(ttl == null ? 1 : Math.max(1, ttl))));
            }
        } catch (TooManyRequestsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (redisRequired) throw redisUnavailable(ex);
            warnRedisFallback(ex);
            localRejectIfBlocked(namespace, subjectKey, blockedMsgFmt);
        }
    }

    public void reset(String namespace, String subjectKey) {
        String key = key(namespace, subjectKey);
        try {
            redis.delete(List.of(key + ":count", key + ":blocked"));
        } catch (RuntimeException ex) {
            if (redisRequired) throw redisUnavailable(ex);
            warnRedisFallback(ex);
        }
        fallbackStates.remove(namespace + ":" + normalize(subjectKey));
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        fallbackStates.entrySet().removeIf(entry -> {
            RateState state = entry.getValue();
            synchronized (state) {
                return !(state.blockedUntil != null && now.isBefore(state.blockedUntil))
                        && (state.expiresAt == null || now.isAfter(state.expiresAt));
            }
        });
    }

    private void localCheckAndRecord(
            String namespace, String subjectKey, int maxRequests, Duration window,
            Duration blockDuration, String blockedMsgFmt, String triggerMsg) {
        String key = namespace + ":" + normalize(subjectKey);
        RateState state = fallbackStates.computeIfAbsent(key, ignored -> new RateState(LocalDateTime.now()));
        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {
                throw new TooManyRequestsException(String.format(blockedMsgFmt,
                        minutes(Duration.between(now, state.blockedUntil).getSeconds())));
            }
            if (state.blockedUntil != null && !now.isBefore(state.blockedUntil)) {
                state.blockedUntil = null;
                state.windowStart = now;
                state.requestCount = 0;
            }
            if (!now.isBefore(state.windowStart.plus(window))) {
                state.windowStart = now;
                state.requestCount = 0;
            }
            if (state.requestCount >= maxRequests) {
                state.blockedUntil = now.plus(blockDuration);
                state.expiresAt = state.blockedUntil;
                throw new TooManyRequestsException(triggerMsg);
            }
            state.requestCount++;
            state.expiresAt = state.windowStart.plus(window);
        }
    }

    private void localRejectIfBlocked(String namespace, String subjectKey, String blockedMsgFmt) {
        RateState state = fallbackStates.get(namespace + ":" + normalize(subjectKey));
        if (state == null) return;
        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {
                throw new TooManyRequestsException(String.format(blockedMsgFmt,
                        minutes(Duration.between(now, state.blockedUntil).getSeconds())));
            }
        }
    }

    private ResponseStatusException redisUnavailable(RuntimeException cause) {
        log.error("Redis rate limiter unavailable; rejecting request because Redis is required", cause);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Hệ thống giới hạn truy cập tạm thời không khả dụng. Vui lòng thử lại sau.", cause);
    }

    private void warnRedisFallback(RuntimeException cause) {
        log.warn("Redis rate limiter unavailable; falling back to per-instance memory in non-production mode ({})",
                cause.getClass().getSimpleName());
    }

    private String key(String namespace, String subject) {
        return "aloute:rate-limit:" + safeNamespace(namespace) + ":" + sha256(normalize(subject));
    }

    private static String safeNamespace(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,48}")) return "default";
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static long seconds(Duration duration) { return Math.max(1, duration.getSeconds()); }
    private static long minutes(long seconds) { return Math.max(1, (Math.max(1, seconds) + 59) / 60); }
    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static final class RateState {
        private LocalDateTime windowStart;
        private int requestCount;
        private LocalDateTime blockedUntil;
        private LocalDateTime expiresAt;
        private RateState(LocalDateTime now) { this.windowStart = now; this.expiresAt = now; }
    }
}
