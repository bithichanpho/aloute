package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.aloute.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class PresenceServiceConfigurationTest {
    @Test
    void rejectsLeaseRefreshThatCouldExpireBeforeTheNextHeartbeat() {
        assertThrows(IllegalArgumentException.class, () -> new PresenceService(
                mock(IUserRepository.class), mock(FriendService.class),
                mock(SimpMessagingTemplate.class), mock(StringRedisTemplate.class),
                120_000L, 60_000L));
    }

    @Test
    void acceptsRefreshPeriodSafelyBelowTheLeaseLifetime() {
        assertDoesNotThrow(() -> new PresenceService(
                mock(IUserRepository.class), mock(FriendService.class),
                mock(SimpMessagingTemplate.class), mock(StringRedisTemplate.class),
                120_000L, 30_000L));
    }
}
