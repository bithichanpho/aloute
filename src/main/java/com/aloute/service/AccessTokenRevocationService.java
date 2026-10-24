package com.aloute.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/** Token-specific denylist: distributed when Redis is enabled; fail-closed in production. */
@Service
public class AccessTokenRevocationService {
    private final StringRedisTemplate redis;
    private final boolean redisRequired;
    private final ConcurrentHashMap<String, Instant> local = new ConcurrentHashMap<>();

    public AccessTokenRevocationService(StringRedisTemplate redis,
            @Value("${app.security.rate-limit.redis-required:false}") boolean redisRequired) {
        this.redis = redis;
        this.redisRequired = redisRequired;
    }

    public void revoke(String token, Instant expiry) {
        if (token == null || token.isBlank() || expiry == null) return;
        long seconds = Math.max(1L, Duration.between(Instant.now(), expiry).getSeconds() + 1);
        String key = key(token);
        local.put(key, expiry);
        try { redis.opsForValue().set(key, "1", Duration.ofSeconds(seconds)); }
        catch (RuntimeException ex) {
            if (redisRequired) throw new IllegalStateException("Distributed token revocation is unavailable", ex);
        }
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) return false;
        String key = key(token);
        Instant expiry = local.get(key);
        if (expiry != null) {
            if (expiry.isAfter(Instant.now())) return true;
            local.remove(key, expiry);
        }
        try { return Boolean.TRUE.equals(redis.hasKey(key)); }
        catch (RuntimeException ex) {
            if (redisRequired) throw new IllegalStateException("Distributed token revocation is unavailable", ex);
            return false; // local development fallback only; cannot guarantee multi-node invalidation.
        }
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return "auth:access:revoked:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
