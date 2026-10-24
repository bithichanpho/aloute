package com.aloute.service;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Short-lived local authentication cache shared by HTTP JWT and STOMP validation. */
@Component
public class AuthenticationUserCache {
    private final IUserRepository users;
    private final Cache<String, Optional<User>> byUsername;
    private final Cache<Long, Optional<User>> byId;

    public AuthenticationUserCache(IUserRepository users,
            @Value("${app.security.user-cache-ttl:5s}") Duration ttl) {
        this.users = users;
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofSeconds(5) : ttl.compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30) : ttl;
        this.byUsername = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(safeTtl)
                .build();
        this.byId = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(safeTtl)
                .build();
    }

    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = username.trim();
        return byUsername.get(key, users::findByUsername);
    }

    /**
     * Short-lived lookup by id for realtime fan-out (typing/call signals), where the receiver is
     * known only by id. Bounded by the same TTL as {@link #findByUsername(String)}.
     */
    public Optional<User> findById(Long id) {
        if (id == null) return Optional.empty();
        return byId.get(id, users::findById);
    }

    /** Evict one user's authentication snapshot after a committed security change. */
    public void invalidate(String username) {
        if (username == null || username.isBlank()) return;
        String key = username.trim();
        byUsername.invalidate(key);
        // The id-keyed snapshot must not outlive a security change either (logout, password change,
        // deactivation). Scanning at most 10,000 small entries is negligible next to the DB write
        // that triggered this call.
        byId.asMap().values().removeIf(cached -> cached.isPresent() && key.equals(cached.get().getUsername()));
    }

}
