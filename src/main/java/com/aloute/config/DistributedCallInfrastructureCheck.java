package com.aloute.config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Prevents the multi-host calling profile from silently running with per-machine local services.
 * All hosts in this profile must use the same database, Redis, STOMP broker and TURN relay.
 */
@Component
@Profile("call-distributed")
public final class DistributedCallInfrastructureCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DistributedCallInfrastructureCheck.class);
    private static final Pattern JDBC_HOST = Pattern.compile("(?i)^jdbc:postgresql://(\\[[^]]+\\]|[^/:?]+)");

    private final Environment environment;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public DistributedCallInfrastructureCheck(
            Environment environment,
            DataSource dataSource,
            StringRedisTemplate redisTemplate) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireTrue("app.websocket.broker-relay.enabled",
                "The call-distributed profile requires the shared STOMP broker relay.");
        requireTrue("app.calls.distributed-state-enabled",
                "The call-distributed profile requires the shared durable call registry.");

        String dbUrl = required("spring.datasource.url", "DB_URL");
        String dbHost = jdbcHost(dbUrl);
        requireSharedHost("PostgreSQL DB_URL", dbHost);

        String redisUrl = required("spring.data.redis.url", "REDIS_URL");
        String redisHost;
        try {
            URI uri = URI.create(redisUrl);
            if (!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme()))
                throw new IllegalStateException("REDIS_URL must use redis:// or rediss://.");
            redisHost = uri.getHost();
        } catch (IllegalArgumentException invalidUrl) {
            throw new IllegalStateException("REDIS_URL is not a valid redis:// or rediss:// URL.");
        }
        requireSharedHost("REDIS_URL", redisHost);

        String brokerHost = required("app.websocket.broker-relay.host", "APP_WEBSOCKET_BROKER_RELAY_HOST");
        requireSharedHost("APP_WEBSOCKET_BROKER_RELAY_HOST", brokerHost);
        int brokerPort = Integer.parseInt(environment.getProperty("app.websocket.broker-relay.port", "61613"));

        String turnUrls = required("app.webrtc.turn-urls", "APP_WEBRTC_TURN_URLS");
        boolean hasRemoteTurn = java.util.Arrays.stream(turnUrls.split(","))
                .map(String::trim)
                .filter(value -> value.matches("(?i)^turns?:.*"))
                .map(DistributedCallInfrastructureCheck::turnHost)
                .anyMatch(host -> !isLocalHost(host));
        if (!hasRemoteTurn) {
            throw new IllegalStateException(
                    "APP_WEBRTC_TURN_URLS must contain a non-local turn: or turns: URL for cross-network calls.");
        }
        String turnSecret = environment.getProperty("app.webrtc.turn-shared-secret", "");
        if (turnSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "TURN_SHARED_SECRET must be at least 32 UTF-8 bytes and match the public TURN server.");
        }

        verifyDatabase();
        verifyRedis();
        verifyBrokerSocket(brokerHost, brokerPort);
        log.info("Distributed call prerequisites verified: shared PostgreSQL and Redis are reachable; "
                + "the shared STOMP broker port is reachable and a non-local TURN URL is configured.");
    }

    private String required(String property, String envName) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(envName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The call-distributed profile requires " + envName + ".");
        }
        return value.trim();
    }

    private void requireTrue(String property, String message) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            throw new IllegalStateException(message);
        }
    }

    private static String jdbcHost(String jdbcUrl) {
        Matcher matcher = JDBC_HOST.matcher(jdbcUrl);
        if (!matcher.find()) {
            throw new IllegalStateException("DB_URL must be a PostgreSQL JDBC URL: jdbc:postgresql://host:5432/database");
        }
        return unbracket(matcher.group(1));
    }

    private static String turnHost(String turnUrl) {
        String authority = turnUrl.replaceFirst("(?i)^turns?:", "");
        int query = authority.indexOf('?');
        if (query >= 0) authority = authority.substring(0, query);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end > 0 ? authority.substring(1, end) : authority;
        }
        int colon = authority.indexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    private void requireSharedHost(String name, String host) {
        if (host == null || host.isBlank() || host.contains("@") || host.contains("/") || isLocalHost(host)) {
            throw new IllegalStateException(name + " must identify a shared service host. "
                    + "Each machine's localhost is different. Point both app instances to the same shared service "
                    + "over a private VPN (for example, Tailscale/WireGuard) or use one shared Aloute server.");
        }
    }

    private static boolean isLocalHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) return true;
        String host = unbracket(rawHost.trim()).toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("::1")
                || host.equals("0.0.0.0") || host.equals("0:0:0:0:0:0:0:1") || host.equals("::") || host.matches("127(?:\\.\\d{1,3}){3}");
    }

    private static String unbracket(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private void verifyDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new IllegalStateException("Shared PostgreSQL connection is not valid.");
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Cannot connect to the shared PostgreSQL DB. Both machines must use the same DB_URL, DB_USERNAME and DB_PASSWORD.",
                    error);
        }
    }

    private void verifyRedis() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) throw new IllegalStateException("Redis connection factory is not configured.");
        try (RedisConnection connection = factory.getConnection()) {
            String response = connection.ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                throw new IllegalStateException("Shared Redis did not return PONG.");
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Cannot connect to shared Redis. Set REDIS_URL to the same reachable private-VPN endpoint on both machines; "
                            + "do not use localhost in call-distributed mode.",
                    error);
        }
    }

    private static void verifyBrokerSocket(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot reach the shared RabbitMQ STOMP broker at " + host + ":" + port
                            + ". Check APP_WEBSOCKET_BROKER_RELAY_HOST, port 61613 and the private-VPN firewall rule.",
                    error);
        }
    }
}
