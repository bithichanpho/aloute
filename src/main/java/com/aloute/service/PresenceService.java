package com.aloute.service;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Shared online presence in Redis with per-WebSocket-session leases and local refresh. */
@Service
public class PresenceService {
    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final String ONLINE_USERS_KEY = "aloute:{presence}:online-users";

    private static final DefaultRedisScript<Long> REGISTER_SESSION_SCRIPT = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1]); "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now); "
                    + "local before = redis.call('ZCARD', KEYS[1]); "
                    + "redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[3]); "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4])); "
                    + "redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[5]); "
                    + "redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[4])); "
                    + "return before;", Long.class);

    private static final DefaultRedisScript<List<Long>> REMOVE_SESSION_SCRIPT = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1]); "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now); "
                    + "local before = redis.call('ZCARD', KEYS[1]); "
                    + "redis.call('ZREM', KEYS[1], ARGV[2]); "
                    + "local remaining = redis.call('ZCARD', KEYS[1]); "
                    + "if remaining == 0 then redis.call('DEL', KEYS[1]); redis.call('ZREM', KEYS[2], ARGV[4]); "
                    + "else local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES'); "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3])); "
                    + "if latest[2] then redis.call('ZADD', KEYS[2], tonumber(latest[2]), ARGV[4]); end; end; "
                    + "return {before, remaining};", longListType());

    @SuppressWarnings("unchecked")
    private static Class<List<Long>> longListType() {
        return (Class<List<Long>>) (Class<?>) List.class;
    }

    private static final DefaultRedisScript<Long> COUNT_SESSIONS_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[1])); "
                    + "local count = redis.call('ZCARD', KEYS[1]); "
                    + "if count == 0 then redis.call('DEL', KEYS[1]); end; "
                    + "return count;", Long.class);

    private static final DefaultRedisScript<List<Long>> COUNT_MANY_SESSIONS_SCRIPT =
            new DefaultRedisScript<>(
                    "local result = {}; local now = tonumber(ARGV[1]); "
                            + "for i,key in ipairs(KEYS) do "
                            + "redis.call('ZREMRANGEBYSCORE', key, '-inf', now); "
                            + "local count = redis.call('ZCARD', key); "
                            + "if count == 0 then redis.call('DEL', key); end; "
                            + "result[i] = count; end; return result;",
                    longListType());

    private static final DefaultRedisScript<Long> COUNT_ONLINE_USERS_SCRIPT =
            new DefaultRedisScript<>(
                    "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[1])); "
                            + "return redis.call('ZCARD', KEYS[1]);",
                    Long.class);

    private final IUserRepository userRepository;
    private final FriendService friendService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final long connectionTtlMillis;
    private final ConcurrentMap<String, LocalConnection> localConnections = new ConcurrentHashMap<>();
    private final AtomicLong lastRedisWarningAt = new AtomicLong();

    public PresenceService(
            IUserRepository userRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate,
            StringRedisTemplate redis,
            @Value("${app.presence.connection-ttl-ms:120000}") long connectionTtlMillis,
            @Value("${app.presence.refresh-interval-ms:30000}") long refreshIntervalMillis) {
        this.userRepository = userRepository;
        this.friendService = friendService;
        this.messagingTemplate = messagingTemplate;
        this.redis = redis;
        this.connectionTtlMillis = Math.max(60_000L, Math.min(600_000L, connectionTtlMillis));
        if (refreshIntervalMillis <= 0 || refreshIntervalMillis >= this.connectionTtlMillis / 2) {
            throw new IllegalArgumentException(
                    "app.presence.refresh-interval-ms must be positive and less than half app.presence.connection-ttl-ms");
        }
    }

    public void userConnected(String username, String sessionId) {
        String normalizedUsername = normalize(username);
        String normalizedSessionId = normalize(sessionId);
        if (normalizedUsername == null || normalizedSessionId == null) {
            log.debug("Ignoring WebSocket presence connect without username/session id");
            return;
        }
        LocalConnection connection = new LocalConnection(normalizedUsername, normalizedSessionId);
        LocalConnection previous = localConnections.put(normalizedSessionId, connection);
        if (previous != null && !previous.username().equals(normalizedUsername)) {
            removeRemoteSession(previous.username(), previous.sessionId(), false);
        }
        registerRemoteSession(connection, true);
    }

    public void userDisconnected(String username, String sessionId) {
        String normalizedSessionId = normalize(sessionId);
        if (normalizedSessionId == null) {
            log.debug("Ignoring WebSocket presence disconnect without session id");
            return;
        }
        LocalConnection connection = localConnections.remove(normalizedSessionId);
        String resolvedUsername = connection != null ? connection.username() : normalize(username);
        if (resolvedUsername == null) return;
        removeRemoteSession(resolvedUsername, normalizedSessionId, true);
    }

    public boolean isOnlineUsername(String username) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername == null) return false;
        try {
            Long count = redis.execute(
                    COUNT_SESSIONS_SCRIPT,
                    List.of(redisKey(normalizedUsername)),
                    String.valueOf(System.currentTimeMillis()));
            return count != null && count > 0;
        } catch (RuntimeException redisFailure) {
            logRedisFallback(redisFailure);
            return localConnections.values().stream()
                    .anyMatch(connection -> connection.username().equals(normalizedUsername));
        }
    }

    public boolean isOnline(Long userId) {
        if (userId == null) return false;
        return userRepository.findById(userId).map(User::getUsername).map(this::isOnlineUsername).orElse(false);
    }

    /** Counts unique online accounts across all application replicas. */
    public long countOnlineUsers() {
        try {
            Long count = redis.execute(
                    COUNT_ONLINE_USERS_SCRIPT,
                    List.of(ONLINE_USERS_KEY),
                    String.valueOf(System.currentTimeMillis()));
            return count == null ? 0L : Math.max(0L, count);
        } catch (RuntimeException redisFailure) {
            logRedisFallback(redisFailure);
            return localConnections.values().stream()
                    .map(LocalConnection::username)
                    .distinct()
                    .count();
        }
    }

    /** Resolves a whole sidebar in one Redis round trip instead of one call per friend. */
    public Set<String> onlineUsernames(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return Set.of();
        List<String> normalized = usernames.stream()
                .map(PresenceService::normalize)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) return Set.of();
        try {
            List<Long> counts = redis.execute(
                    COUNT_MANY_SESSIONS_SCRIPT,
                    normalized.stream().map(this::redisKey).toList(),
                    String.valueOf(System.currentTimeMillis()));
            if (counts == null) return Set.of();
            Set<String> online = new HashSet<>();
            for (int i = 0; i < Math.min(normalized.size(), counts.size()); i++) {
                if (asLong(counts.get(i)) > 0) online.add(normalized.get(i));
            }
            return online;
        } catch (RuntimeException redisFailure) {
            logRedisFallback(redisFailure);
            Set<String> local = localConnections.values().stream()
                    .map(LocalConnection::username)
                    .collect(java.util.stream.Collectors.toSet());
            return normalized.stream().filter(local::contains).collect(java.util.stream.Collectors.toSet());
        }
    }

    /** Refresh this node's leases; abandoned leases from crashed nodes expire automatically. */
    @Scheduled(fixedDelayString = "${app.presence.refresh-interval-ms:30000}")
    public void refreshLocalConnections() {
        for (LocalConnection connection : localConnections.values()) {
            registerRemoteSession(connection, true);
        }
    }

    private void registerRemoteSession(LocalConnection connection, boolean emitOnlineTransition) {
        long now = System.currentTimeMillis();
        long expiresAt = now + connectionTtlMillis;
        try {
            Long previousCount = redis.execute(
                    REGISTER_SESSION_SCRIPT,
                    List.of(redisKey(connection.username()), ONLINE_USERS_KEY),
                    String.valueOf(now),
                    String.valueOf(expiresAt),
                    connection.sessionId(),
                    String.valueOf(connectionTtlMillis + connectionTtlMillis),
                    presenceMember(connection.username()));
            if (emitOnlineTransition && previousCount != null && previousCount == 0L) {
                broadcastPresence(connection.username(), true);
            }
        } catch (RuntimeException redisFailure) {
            logRedisFallback(redisFailure);
        }
    }

    private void removeRemoteSession(String username, String sessionId, boolean emitOfflineTransition) {
        long now = System.currentTimeMillis();
        try {
            List<Long> result = redis.execute(
                    REMOVE_SESSION_SCRIPT,
                    List.of(redisKey(username), ONLINE_USERS_KEY),
                    String.valueOf(now),
                    sessionId,
                    String.valueOf(connectionTtlMillis + connectionTtlMillis),
                    presenceMember(username));
            if (emitOfflineTransition && result != null && result.size() >= 2
                    && asLong(result.get(0)) > 0 && asLong(result.get(1)) == 0) {
                broadcastPresence(username, false);
            }
        } catch (RuntimeException redisFailure) {
            logRedisFallback(redisFailure);
        }
    }

    private void broadcastPresence(String username, boolean online) {
        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                Map<String, Object> payload = Map.of("userId", user.getId(), "online", online);
                List<Long> friendIds = friendService.getMutualFriendIds(user.getId());
                for (User friend : userRepository.findAllById(friendIds)) {
                    if (friend.isActive()) {
                        messagingTemplate.convertAndSendToUser(
                                friend.getUsername(), "/queue/presence", payload);
                    }
                }
            });
        } catch (RuntimeException failure) {
            log.warn("Presence transition could not be broadcast (cause={})",
                    failure.getClass().getSimpleName());
        }
    }

    private String redisKey(String username) {
        return "aloute:{presence}:user:" + presenceMember(username);
    }

    private String presenceMember(String username) {
        return sha256(username);
    }

    private void logRedisFallback(RuntimeException error) {
        long now = System.currentTimeMillis();
        long previous = lastRedisWarningAt.get();
        if (now - previous >= 30_000L && lastRedisWarningAt.compareAndSet(previous, now)) {
            log.warn("Redis presence operation failed; using this node's local sessions temporarily ({})",
                    error.getClass().getSimpleName());
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record LocalConnection(String username, String sessionId) {}
}
