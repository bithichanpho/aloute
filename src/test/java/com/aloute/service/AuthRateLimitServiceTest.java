package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.TooManyRequestsException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AuthRateLimitServiceTest {

    @Test
    void productionPolicyFailsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redis = unavailableRedis();
        AuthRateLimitService service = new AuthRateLimitService(redis, true);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.checkAndRecord("LOGIN", "user@example.com", 5,
                        Duration.ofMinutes(1), Duration.ofMinutes(5),
                        "Blocked for %d minutes", "Blocked"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
    }

    @Test
    void localPolicyStillEnforcesLimitDuringRedisOutage() {
        StringRedisTemplate redis = unavailableRedis();
        AuthRateLimitService service = new AuthRateLimitService(redis, false);

        assertDoesNotThrow(() -> service.checkAndRecord("UPLOAD", "7", 2,
                Duration.ofMinutes(1), Duration.ofMinutes(5),
                "Blocked for %d minutes", "Blocked"));
        assertDoesNotThrow(() -> service.checkAndRecord("UPLOAD", "7", 2,
                Duration.ofMinutes(1), Duration.ofMinutes(5),
                "Blocked for %d minutes", "Blocked"));
        assertThrows(TooManyRequestsException.class,
                () -> service.checkAndRecord("UPLOAD", "7", 2,
                        Duration.ofMinutes(1), Duration.ofMinutes(5),
                        "Blocked for %d minutes", "Blocked"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private StringRedisTemplate unavailableRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        return redis;
    }
}
