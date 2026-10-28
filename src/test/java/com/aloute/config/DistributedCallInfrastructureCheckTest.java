package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

class DistributedCallInfrastructureCheckTest {
    private final DataSource database = mock(DataSource.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private MockEnvironment configured() {
        return new MockEnvironment().withProperty("app.websocket.broker-relay.enabled", "true")
                .withProperty("app.calls.distributed-state-enabled", "true")
                .withProperty("spring.datasource.url", "jdbc:postgresql://shared-db.internal:5432/test")
                .withProperty("spring.data.redis.url", "rediss://shared-redis.internal:6379")
                .withProperty("app.websocket.broker-relay.host", "shared-broker.internal")
                .withProperty("app.webrtc.turn-urls", "turn:relay.example.invalid:3478")
                .withProperty("app.webrtc.turn-shared-secret", "test-only-shared-secret-32-bytes-long");
    }

    @Test
    void rejectsMissingSharedConfigurationBeforeAnyExternalIo() {
        for (String property : java.util.List.of("app.websocket.broker-relay.enabled", "app.calls.distributed-state-enabled")) {
            var env = configured().withProperty(property, "false");
            assertThrows(IllegalStateException.class, () -> new DistributedCallInfrastructureCheck(env, database, redis)
                    .run(new DefaultApplicationArguments()));
        }
        verifyNoInteractions(database, redis);
    }

    @Test
    void rejectsLoopbackServicesInvalidRedisSchemeAndLocalTurnWithoutLeakingUrlCredentials() {
        for (var entry : java.util.Map.of(
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/test",
                "spring.data.redis.url", "https://user:test-fixture-password@shared-redis.internal:6379",
                "app.websocket.broker-relay.host", "localhost",
                "app.webrtc.turn-urls", "turn:[::1]:3478",
                "app.webrtc.turn-shared-secret", "short").entrySet()) {
            var env = configured().withProperty(entry.getKey(), entry.getValue());
            var error = assertThrows(IllegalStateException.class, () -> new DistributedCallInfrastructureCheck(env, database, redis)
                    .run(new DefaultApplicationArguments()));
            assertFalse(error.toString().contains("test-fixture-password"));
        }
        verifyNoInteractions(database, redis);
    }

    @Test
    void sharedContainerDnsIsAllowedAndDatabaseFailureRemainsObservable() throws Exception {
        var env = configured().withProperty("spring.data.redis.url", "redis://redis:6379")
                .withProperty("app.websocket.broker-relay.host", "rabbitmq");
        when(database.getConnection()).thenThrow(new java.sql.SQLException("test database unavailable"));
        var error = assertThrows(IllegalStateException.class, () -> new DistributedCallInfrastructureCheck(env, database, redis)
                .run(new DefaultApplicationArguments()));
        assertTrue(error.getMessage().startsWith("Cannot connect to the shared PostgreSQL"));
        verify(database).getConnection(); verifyNoInteractions(redis);
    }
}
